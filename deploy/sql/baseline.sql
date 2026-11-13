-- TokenWorks Mobile native transport baseline (dsh 0.2.1-rc.5).
-- Create a NEW database with this file; startup refuses the previous business schema.
--
-- PostgreSQL database dump
--

\restrict 5YdJvhqrWkFWquPuzJrCbRRJZ4be5HDplK2KVLQUjg9IrnDLLylTSOnRCOSfYFt

-- Dumped from database version 18.6 (Ubuntu 18.6-0ubuntu0.26.04.1)
-- Dumped by pg_dump version 18.6 (Ubuntu 18.6-0ubuntu0.26.04.1)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: pgcrypto; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;


--
-- Name: EXTENSION pgcrypto; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION pgcrypto IS 'cryptographic functions';


--
-- Name: app_release_artifact_guard(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.app_release_artifact_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
declare current_epoch uuid; current_phase text; audit_id bigint;
begin
    if tg_op<>'INSERT' then
        raise exception 'APK artifact identities are immutable' using errcode='23514';
    end if;
    if tg_when='BEFORE' then
        select epoch,phase into current_epoch,current_phase from remote_recovery_state where slot=1 for share;
        if current_phase is distinct from 'ready' or current_epoch is distinct from new.registered_epoch then
            raise exception 'APK artifact registration recovery is fenced' using errcode='23514';
        end if;
        new.registered_at=clock_timestamp();
    else
        insert into audit_log(actor_type,actor_id,action,result,metadata)
        values('system',current_user,'app.release.register','success',
            jsonb_build_object('release_id',new.release_id,'package_name',new.package_name,
                'version_code',new.version_code,'identity_sha256',encode(new.identity_sha256,'hex'),
                'database_epoch',new.registered_epoch)) returning id into audit_id;
        if audit_id is null then
            raise exception 'APK artifact registration audit is unavailable' using errcode='23514';
        end if;
    end if;
    return new;
end $$;


--
-- Name: app_release_policy_change_guard(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.app_release_policy_change_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
declare current_epoch uuid; current_phase text; prior app_release_policies%rowtype; audit_id bigint; changed_count integer;
begin
    if tg_op<>'INSERT' then raise exception 'release policy operation receipts are immutable' using errcode='23514'; end if;
    if tg_when='BEFORE' then
        select epoch,phase into current_epoch,current_phase from remote_recovery_state where slot=1 for share;
        if current_phase is distinct from 'ready' or current_epoch is distinct from new.database_epoch then
            raise exception 'release policy change recovery is fenced' using errcode='23514';
        end if;
        select * into prior from app_release_policies where package_name=new.package_name for update;
        if not found or prior.policy_sha256<>new.previous_sha256 or prior.policy_sha256=new.policy_sha256 then
            raise exception 'release policy compare and set failed' using errcode='23514';
        end if;
        new.revision=prior.revision+1; new.changed_at=clock_timestamp();
    else
        update app_release_policies set policy_sha256=new.policy_sha256,revision=new.revision,changed_epoch=new.database_epoch
            where package_name=new.package_name and policy_sha256=new.previous_sha256 and revision=new.revision-1;
        get diagnostics changed_count=row_count;
        if changed_count<>1 then raise exception 'release policy update is unavailable' using errcode='23514'; end if;
        insert into audit_log(actor_type,actor_id,action,result,metadata)
        values('system',current_user,'app.release.policy.replace','success',
            jsonb_build_object('operation_id',new.operation_id,'package_name',new.package_name,
                'previous_sha256',encode(new.previous_sha256,'hex'),'policy_sha256',encode(new.policy_sha256,'hex'),
                'revision',new.revision,'database_epoch',new.database_epoch)) returning id into audit_id;
        if audit_id is null then raise exception 'release policy change audit is unavailable' using errcode='23514'; end if;
    end if;
    return new;
end $$;


--
-- Name: app_release_policy_confirmation_guard(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.app_release_policy_confirmation_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
declare current_epoch uuid; current_phase text; prior app_release_policies%rowtype; audit_id bigint; changed_count integer;
begin
    if tg_op<>'INSERT' then raise exception 'release policy confirmations are immutable' using errcode='23514'; end if;
    if tg_when='BEFORE' then
        select epoch,phase into current_epoch,current_phase from remote_recovery_state where slot=1 for share;
        if current_phase is distinct from 'ready' or current_epoch is distinct from new.database_epoch then
            raise exception 'release policy confirmation recovery is fenced' using errcode='23514';
        end if;
        select * into prior from app_release_policies where package_name=new.package_name for update;
        if not found or prior.validated_epoch=current_epoch or prior.policy_sha256<>new.policy_sha256 then
            raise exception 'release policy confirmation conflicts with current evidence' using errcode='23514';
        end if;
        new.revision=prior.revision; new.confirmed_at=clock_timestamp();
    else
        update app_release_policies set validated_epoch=new.database_epoch
            where package_name=new.package_name and policy_sha256=new.policy_sha256 and revision=new.revision
                and validated_epoch<>new.database_epoch;
        get diagnostics changed_count=row_count;
        if changed_count<>1 then raise exception 'release policy confirmation is unavailable' using errcode='23514'; end if;
        insert into audit_log(actor_type,actor_id,action,result,metadata)
        values('system',current_user,'app.release.policy.confirm','success',
            jsonb_build_object('operation_id',new.operation_id,'package_name',new.package_name,
                'policy_sha256',encode(new.policy_sha256,'hex'),'revision',new.revision,
                'database_epoch',new.database_epoch)) returning id into audit_id;
        if audit_id is null then raise exception 'release policy confirmation audit is unavailable' using errcode='23514'; end if;
    end if;
    return new;
end $$;


--
-- Name: app_release_policy_guard(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.app_release_policy_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
declare current_epoch uuid; current_phase text; audit_id bigint; recorded_time timestamptz;
begin
    if tg_op not in ('INSERT','UPDATE') then raise exception 'release policy history cannot be removed' using errcode='23514'; end if;
    select epoch,phase into current_epoch,current_phase from remote_recovery_state where slot=1 for share;
    if current_phase is distinct from 'ready' then
        raise exception 'release policy recovery is fenced' using errcode='23514';
    end if;
    if tg_op='UPDATE' and row(new.package_name,new.policy_sha256,new.revision,new.changed_epoch,new.changed_at)
        is not distinct from row(old.package_name,old.policy_sha256,old.revision,old.changed_epoch,old.changed_at) then
        -- Confirmation changes only the acceptance epoch, never publication facts.
        if old.validated_epoch=current_epoch or new.validated_epoch<>current_epoch or not exists(
            select 1 from app_release_policy_confirmations where package_name=new.package_name
                and database_epoch=current_epoch and policy_sha256=new.policy_sha256 and revision=new.revision) then
            raise exception 'release policy requires current recovery confirmation' using errcode='23514';
        end if;
        return new;
    end if;
    if current_epoch is distinct from new.changed_epoch then
        raise exception 'release policy change recovery is fenced' using errcode='23514';
    end if;
    if tg_op='UPDATE' then
        if new.package_name<>old.package_name or new.revision<>old.revision+1 or new.policy_sha256=old.policy_sha256 then
            raise exception 'invalid release policy transition' using errcode='23514';
        end if;
        select changed_at into recorded_time from app_release_policy_changes
            where package_name=new.package_name and revision=new.revision and database_epoch=new.changed_epoch
                and previous_sha256=old.policy_sha256 and policy_sha256=new.policy_sha256;
        if not found then raise exception 'release policy requires retained operation' using errcode='23514'; end if;
        new.changed_at=recorded_time; new.validated_epoch=current_epoch;
    elsif tg_when='BEFORE' then
        new.revision=1; new.changed_at=clock_timestamp(); new.validated_epoch=current_epoch;
    else
        insert into audit_log(actor_type,actor_id,action,result,metadata)
        values('system',current_user,'app.release.policy.register','success',
            jsonb_build_object('package_name',new.package_name,'policy_sha256',encode(new.policy_sha256,'hex'),
                'revision',new.revision,'database_epoch',new.changed_epoch)) returning id into audit_id;
        if audit_id is null then raise exception 'release policy audit is unavailable' using errcode='23514'; end if;
    end if;
    return new;
end $$;


--
-- Name: audit_log_immutable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.audit_log_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
    raise exception 'audit history is append-only' using errcode='23514';
end $$;


--
-- Name: audit_log_stamp(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.audit_log_stamp() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
    new.ts=clock_timestamp();
    new.database_epoch=remote_database_epoch();
    if new.database_epoch is null then raise exception 'audit database epoch is unavailable' using errcode='23514'; end if;
    new.producer_xid=pg_current_xact_id();
    return new;
end $$;


--
-- Name: control_hot_ack_retention_guard(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.control_hot_ack_retention_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
    if not exists(select 1 from remote_recovery_state where slot=1 and phase='ready') then
        raise exception 'control recovery state is unavailable' using errcode='23514';
    end if;
    return null;
end $$;


--
-- Name: control_outbox_identity(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.control_outbox_identity() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
    if tg_op='INSERT' then
        new.producer_xid=pg_current_xact_id();new.database_epoch=remote_database_epoch();return new;
    end if;
    if row(new.id,new.org_id,new.topic,new.payload,new.created_at,new.producer_xid,new.database_epoch)
        is distinct from row(old.id,old.org_id,old.topic,old.payload,old.created_at,old.producer_xid,old.database_epoch) then
        raise exception 'control event identity and payload are immutable' using errcode='23514';
    end if;
    return new;
end $$;


--
-- Name: control_table_no_truncate(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.control_table_no_truncate() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
    raise exception 'control history cannot be truncated' using errcode='23514';
end $$;


--
-- Name: enrollment_admin_denial_immutable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.enrollment_admin_denial_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
    raise exception 'enrollment administrator denial evidence is immutable' using errcode='23514';
end $$;


--
-- Name: enrollment_admin_denial_owner(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.enrollment_admin_denial_owner() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
declare organization uuid;
begin
    select h.org_id into organization from enrollments e join hosts h on h.id=e.host_id
        where e.id=new.enrollment_id and e.state='denied' and e.admin_approval_required
        and e.decided_by=new.denied_by and e.decided_at<=new.denied_at and e.expires_at>new.denied_at
        for update of e;
    if organization is null then
        raise exception 'denial must match a timely administrator decision' using errcode='23514';
    end if;
    perform 1 from users u join org_memberships m on m.user_id=u.id and m.org_id=organization and m.role='admin'
        join devices d on d.user_id=u.id and d.id=new.device_id and d.key_version=new.device_key_version and d.revoked_at is null
        where u.id=new.denied_by and u.status='active' for share of u,m,d;
    if not found then
        raise exception 'denial must retain current administrator device provenance' using errcode='23514';
    end if;
    return new;
end $$;


--
-- Name: enrollment_admin_denial_terminal(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.enrollment_admin_denial_terminal() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
    if new is distinct from old and exists(select 1 from enrollment_admin_denials where enrollment_id=old.id) then
        raise exception 'recorded enrollment denial is terminal' using errcode='23514';
    end if;
    return new;
end $$;


--
-- Name: enrollment_admin_gate(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.enrollment_admin_gate() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
declare required boolean;
begin
    if tg_op='UPDATE' and old.state='approved' then
        if new.admin_approval_required is distinct from old.admin_approval_required
           or new.admin_approved_by is distinct from old.admin_approved_by
           or new.admin_approved_device_id is distinct from old.admin_approved_device_id
           or new.admin_approved_device_key_version is distinct from old.admin_approved_device_key_version
           or new.admin_approved_at is distinct from old.admin_approved_at
           or new.admin_review_sha256 is distinct from old.admin_review_sha256 then
            raise exception 'approved enrollment administration is immutable' using errcode='23514';
        end if;
        return new;
    end if;
    select o.enrollment_admin_approval_required into strict required
        from orgs o join hosts h on h.org_id=o.id where h.id=new.host_id for share of o;
    if tg_op='INSERT' then
        new.admin_approval_required := required;
    else
        new.admin_approval_required := old.admin_approval_required or required or new.admin_approval_required;
    end if;
    return new;
end $$;


--
-- Name: enrollment_admin_policy_tighten(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.enrollment_admin_policy_tighten() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
    if new.enrollment_admin_approval_required and not old.enrollment_admin_approval_required then
        update enrollments e set admin_approval_required=true from hosts h
            where h.id=e.host_id and h.org_id=new.id and e.state in('pending','user_confirmed')
            and not e.admin_approval_required;
    end if;
    return new;
end $$;


--
-- Name: enrollment_observed_terminal(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.enrollment_observed_terminal() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
    if new is distinct from old and exists(select 1 from enrollment_terminal_checks where enrollment_id=old.id) then
        raise exception 'observed enrollment terminal state cannot change' using errcode='23514';
    end if;
    return new;
end $$;


--
-- Name: enrollment_terminal_check_immutable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.enrollment_terminal_check_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
    raise exception 'observed enrollment terminal evidence is immutable' using errcode='23514';
end $$;


--
-- Name: enrollment_terminal_check_owner(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.enrollment_terminal_check_owner() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
    perform 1 from enrollments e join installations i on i.id=e.installation_id join runtimes r on r.id=e.runtime_id
        where e.id=new.enrollment_id and e.state=new.state and e.requested_by=new.owner_user_id
        and i.owner_user_id=new.owner_user_id and i.public_key_thumbprint=new.installation_key_thumbprint
        and r.installation_id=i.id and r.revoked_at is not null for update of e;
    if not found then
        raise exception 'terminal check must match a closed owned enrollment' using errcode='23514';
    end if;
    return new;
end $$;


--
-- Name: remote_database_epoch(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.remote_database_epoch() RETURNS uuid
    LANGUAGE sql STABLE
    AS $$
    select epoch from remote_recovery_state where slot=1
$$;


--
-- Name: revoke_inactive_account_sessions(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.revoke_inactive_account_sessions() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
    if new.status <> 'active' then
        delete from account_sessions where user_id=new.id;
    end if;
    return new;
end $$;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: account_login_limits; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.account_login_limits (
    bucket_hash bytea NOT NULL,
    started_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    attempts integer DEFAULT 1 NOT NULL,
    CONSTRAINT account_login_limits_attempts_check CHECK ((attempts > 0)),
    CONSTRAINT account_login_limits_bucket_hash_check CHECK ((octet_length(bucket_hash) = 32))
);


--
-- Name: account_sessions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.account_sessions (
    token_hash bytea NOT NULL,
    csrf_hash bytea NOT NULL,
    user_id uuid NOT NULL,
    password_version bigint NOT NULL,
    database_epoch uuid NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    CONSTRAINT account_sessions_check CHECK ((expires_at > created_at)),
    CONSTRAINT account_sessions_csrf_hash_check CHECK ((octet_length(csrf_hash) = 32)),
    CONSTRAINT account_sessions_token_hash_check CHECK ((octet_length(token_hash) = 32))
);


--
-- Name: app_release_artifacts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.app_release_artifacts (
    release_id uuid NOT NULL,
    package_name text NOT NULL,
    version_code bigint NOT NULL,
    version_name text NOT NULL,
    sha256 text NOT NULL,
    size_bytes bigint NOT NULL,
    signing_cert_sha256 text NOT NULL,
    minimum_protocol_version bigint NOT NULL,
    maximum_protocol_version bigint NOT NULL,
    minimum_android_api bigint NOT NULL,
    identity_sha256 bytea NOT NULL,
    registered_epoch uuid NOT NULL,
    registered_at timestamp with time zone NOT NULL,
    CONSTRAINT app_release_artifacts_check CHECK (((maximum_protocol_version >= minimum_protocol_version) AND (maximum_protocol_version <= 32))),
    CONSTRAINT app_release_artifacts_identity_sha256_check CHECK ((octet_length(identity_sha256) = 32)),
    CONSTRAINT app_release_artifacts_minimum_android_api_check CHECK (((minimum_android_api >= 26) AND (minimum_android_api <= 100))),
    CONSTRAINT app_release_artifacts_minimum_protocol_version_check CHECK (((minimum_protocol_version >= 1) AND (minimum_protocol_version <= 32))),
    CONSTRAINT app_release_artifacts_package_name_check CHECK ((((length(package_name) >= 3) AND (length(package_name) <= 255)) AND (package_name ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$'::text))),
    CONSTRAINT app_release_artifacts_sha256_check CHECK ((sha256 ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT app_release_artifacts_signing_cert_sha256_check CHECK ((signing_cert_sha256 ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT app_release_artifacts_size_bytes_check CHECK (((size_bytes >= 1) AND (size_bytes <= 536870912))),
    CONSTRAINT app_release_artifacts_version_code_check CHECK (((version_code >= 1) AND (version_code <= '9007199254740991'::bigint))),
    CONSTRAINT app_release_artifacts_version_name_check CHECK (((octet_length(version_name) >= 1) AND (octet_length(version_name) <= 64)))
);


--
-- Name: app_release_policies; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.app_release_policies (
    package_name text NOT NULL,
    policy_sha256 bytea NOT NULL,
    revision bigint NOT NULL,
    changed_epoch uuid NOT NULL,
    changed_at timestamp with time zone NOT NULL,
    validated_epoch uuid NOT NULL,
    CONSTRAINT app_release_policies_package_name_check CHECK ((((length(package_name) >= 3) AND (length(package_name) <= 255)) AND (package_name ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$'::text))),
    CONSTRAINT app_release_policies_policy_sha256_check CHECK ((octet_length(policy_sha256) = 32)),
    CONSTRAINT app_release_policies_revision_check CHECK ((revision > 0))
);


--
-- Name: app_release_policy_changes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.app_release_policy_changes (
    operation_id uuid NOT NULL,
    database_epoch uuid NOT NULL,
    package_name text NOT NULL,
    previous_sha256 bytea NOT NULL,
    policy_sha256 bytea NOT NULL,
    revision bigint NOT NULL,
    changed_at timestamp with time zone NOT NULL,
    CONSTRAINT app_release_policy_changes_check CHECK (((octet_length(policy_sha256) = 32) AND (policy_sha256 <> previous_sha256))),
    CONSTRAINT app_release_policy_changes_previous_sha256_check CHECK ((octet_length(previous_sha256) = 32)),
    CONSTRAINT app_release_policy_changes_revision_check CHECK ((revision > 1))
);


--
-- Name: app_release_policy_confirmations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.app_release_policy_confirmations (
    operation_id uuid NOT NULL,
    database_epoch uuid NOT NULL,
    package_name text NOT NULL,
    policy_sha256 bytea NOT NULL,
    revision bigint NOT NULL,
    confirmed_at timestamp with time zone NOT NULL,
    CONSTRAINT app_release_policy_confirmations_policy_sha256_check CHECK ((octet_length(policy_sha256) = 32)),
    CONSTRAINT app_release_policy_confirmations_revision_check CHECK ((revision > 0))
);


--
-- Name: app_releases; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.app_releases (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    platform text NOT NULL,
    version text NOT NULL,
    version_code bigint NOT NULL,
    sha256 text NOT NULL,
    object_key text NOT NULL,
    rollout integer DEFAULT 0 NOT NULL,
    mandatory boolean DEFAULT false NOT NULL,
    notes text DEFAULT ''::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT app_releases_rollout_check CHECK (((rollout >= 0) AND (rollout <= 100))),
    CONSTRAINT app_releases_sha256_check CHECK ((sha256 ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT app_releases_version_code_check CHECK ((version_code > 0))
);


--
-- Name: audit_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.audit_log (
    id bigint NOT NULL,
    ts timestamp with time zone DEFAULT now() NOT NULL,
    org_id uuid,
    actor_type text NOT NULL,
    actor_id text NOT NULL,
    device_id uuid,
    runtime_id uuid,
    session_id uuid,
    action text NOT NULL,
    result text NOT NULL,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    database_epoch uuid,
    producer_xid xid8,
    CONSTRAINT audit_log_actor_type_check CHECK ((actor_type = ANY (ARRAY['user'::text, 'device'::text, 'installation'::text, 'system'::text]))),
    CONSTRAINT audit_log_metadata_check CHECK ((jsonb_typeof(metadata) = 'object'::text))
);


--
-- Name: audit_log_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.audit_log ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.audit_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: control_consumers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.control_consumers (
    node_id uuid NOT NULL,
    generation uuid NOT NULL,
    coverage pg_snapshot NOT NULL,
    durable boolean NOT NULL,
    lease_until timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    renewed_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    relay_origin text,
    database_epoch uuid DEFAULT public.remote_database_epoch() NOT NULL
);


--
-- Name: control_event_acks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.control_event_acks (
    node_id uuid NOT NULL,
    event_id uuid NOT NULL,
    applied_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL
);


--
-- Name: TABLE control_event_acks; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.control_event_acks IS 'Independent local-apply acknowledgements; one relay cannot consume another relay delivery. Compacted only behind that consumer snapshot.';


--
-- Name: control_outbox; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.control_outbox (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    org_id uuid,
    topic text NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    published_at timestamp with time zone,
    producer_xid xid8 DEFAULT pg_current_xact_id() NOT NULL,
    database_epoch uuid DEFAULT public.remote_database_epoch() NOT NULL,
    CONSTRAINT control_outbox_payload_check CHECK ((jsonb_typeof(payload) = 'object'::text))
);


--
-- Name: devices; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.devices (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    name text NOT NULL,
    approval_public_jwk jsonb NOT NULL,
    approval_key_thumbprint text NOT NULL,
    proof_public_jwk jsonb NOT NULL,
    proof_key_thumbprint text NOT NULL,
    key_version bigint DEFAULT 1 NOT NULL,
    push_provider text,
    push_token_ciphertext bytea,
    last_seen_at timestamp with time zone,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT devices_approval_public_jwk_check CHECK ((jsonb_typeof(approval_public_jwk) = 'object'::text)),
    CONSTRAINT devices_check CHECK ((approval_key_thumbprint <> proof_key_thumbprint)),
    CONSTRAINT devices_key_version_check CHECK ((key_version > 0)),
    CONSTRAINT devices_name_check CHECK (((length(name) >= 1) AND (length(name) <= 160))),
    CONSTRAINT devices_proof_public_jwk_check CHECK ((jsonb_typeof(proof_public_jwk) = 'object'::text)),
    CONSTRAINT devices_push_provider_check CHECK (((push_provider = ANY (ARRAY['fcm'::text, 'vendor'::text])) OR (push_provider IS NULL)))
);


--
-- Name: dpop_proof_buckets; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dpop_proof_buckets (
    scope_sha256 bytea NOT NULL,
    bucket smallint NOT NULL,
    entry_count integer DEFAULT 0 NOT NULL,
    last_cleanup_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT dpop_proof_buckets_bucket_check CHECK (((bucket >= 0) AND (bucket <= 255))),
    CONSTRAINT dpop_proof_buckets_entry_count_check CHECK (((entry_count >= 0) AND (entry_count <= 65536)))
);


--
-- Name: dpop_proof_entries; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dpop_proof_entries (
    scope_sha256 bytea NOT NULL,
    bucket smallint NOT NULL,
    kind smallint NOT NULL,
    entry_sha256 bytea NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    CONSTRAINT dpop_proof_entries_entry_sha256_check CHECK ((octet_length(entry_sha256) = 32)),
    CONSTRAINT dpop_proof_entries_kind_check CHECK ((kind = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE dpop_proof_entries; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.dpop_proof_entries IS 'Shared one-use DPoP nonce/replay digests only; no raw nonce, jti, token or identity. Bucket locking makes rotation atomic across processes.';


--
-- Name: dpop_proof_resources; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dpop_proof_resources (
    scope_sha256 bytea NOT NULL,
    entries_per_bucket integer NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT dpop_proof_resources_entries_per_bucket_check CHECK (((entries_per_bucket >= 64) AND (entries_per_bucket <= 65536))),
    CONSTRAINT dpop_proof_resources_scope_sha256_check CHECK ((octet_length(scope_sha256) = 32))
);


--
-- Name: enrollment_admin_denials; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.enrollment_admin_denials (
    enrollment_id uuid NOT NULL,
    denied_by uuid NOT NULL,
    device_id uuid NOT NULL,
    device_key_version bigint NOT NULL,
    review_sha256 bytea NOT NULL,
    denied_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT enrollment_admin_denials_device_key_version_check CHECK ((device_key_version > 0)),
    CONSTRAINT enrollment_admin_denials_review_sha256_check CHECK ((octet_length(review_sha256) = 32))
);


--
-- Name: enrollment_terminal_checks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.enrollment_terminal_checks (
    enrollment_id uuid NOT NULL,
    state text NOT NULL,
    owner_user_id uuid NOT NULL,
    installation_key_thumbprint text NOT NULL,
    observed_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT enrollment_terminal_checks_installation_key_thumbprint_check CHECK ((length(installation_key_thumbprint) = 43)),
    CONSTRAINT enrollment_terminal_checks_state_check CHECK ((state = ANY (ARRAY['denied'::text, 'expired'::text, 'cancelled'::text])))
);


--
-- Name: enrollments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.enrollments (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    host_id uuid NOT NULL,
    installation_id uuid NOT NULL,
    requested_by uuid NOT NULL,
    code_hash bytea NOT NULL,
    state text DEFAULT 'pending'::text NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    user_confirmed_by uuid,
    user_confirmed_at timestamp with time zone,
    host_confirmed_at timestamp with time zone,
    decided_by uuid,
    decided_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    request_id uuid NOT NULL,
    request_sha256 bytea NOT NULL,
    runtime_id uuid NOT NULL,
    user_confirmed_device_id uuid,
    user_confirmed_device_key_version bigint,
    admin_approval_required boolean DEFAULT false NOT NULL,
    admin_approved_by uuid,
    admin_approved_device_id uuid,
    admin_approved_device_key_version bigint,
    admin_approved_at timestamp with time zone,
    admin_review_sha256 bytea,
    CONSTRAINT enrollment_admin_activation_gate CHECK (((state <> 'approved'::text) OR (NOT admin_approval_required) OR (admin_approved_by IS NOT NULL))),
    CONSTRAINT enrollment_admin_approval_shape CHECK ((((admin_approved_by IS NULL) AND (admin_approved_device_id IS NULL) AND (admin_approved_device_key_version IS NULL) AND (admin_approved_at IS NULL) AND (admin_review_sha256 IS NULL)) OR ((admin_approved_by IS NOT NULL) AND (admin_approved_device_id IS NOT NULL) AND (admin_approved_device_key_version IS NOT NULL) AND (admin_approved_device_key_version > 0) AND (admin_approved_at IS NOT NULL) AND (admin_review_sha256 IS NOT NULL) AND (octet_length(admin_review_sha256) = 32) AND admin_approval_required AND (state = 'approved'::text) AND (user_confirmed_by IS NOT NULL) AND (user_confirmed_at IS NOT NULL) AND (user_confirmed_device_id IS NOT NULL) AND (host_confirmed_at IS NOT NULL)))),
    CONSTRAINT enrollment_confirmation_device_shape CHECK ((((user_confirmed_device_id IS NULL) AND (user_confirmed_device_key_version IS NULL)) OR ((user_confirmed_by IS NOT NULL) AND (user_confirmed_device_id IS NOT NULL) AND (user_confirmed_device_key_version IS NOT NULL) AND (user_confirmed_device_key_version > 0)))),
    CONSTRAINT enrollments_check CHECK ((expires_at > created_at)),
    CONSTRAINT enrollments_request_sha256_length CHECK ((octet_length(request_sha256) = 32)),
    CONSTRAINT enrollments_state_check CHECK ((state = ANY (ARRAY['pending'::text, 'user_confirmed'::text, 'approved'::text, 'denied'::text, 'expired'::text, 'cancelled'::text])))
);


--
-- Name: host_connection_credentials; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.host_connection_credentials (
    token_sha256 bytea NOT NULL,
    installation_id uuid NOT NULL,
    key_version bigint NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT host_connection_credentials_check CHECK ((expires_at > created_at)),
    CONSTRAINT host_connection_credentials_key_version_check CHECK ((key_version > 0)),
    CONSTRAINT host_connection_credentials_token_sha256_check CHECK ((octet_length(token_sha256) = 32))
);


--
-- Name: TABLE host_connection_credentials; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.host_connection_credentials IS 'Short-lived host connection credentials; stores only SHA-256 token digests and installation key versions.';


--
-- Name: hosts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.hosts (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    org_id uuid NOT NULL,
    alias text NOT NULL,
    state text DEFAULT 'pending'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT hosts_alias_check CHECK (((length(alias) >= 1) AND (length(alias) <= 160))),
    CONSTRAINT hosts_state_check CHECK ((state = ANY (ARRAY['pending'::text, 'active'::text, 'revoked'::text])))
);


--
-- Name: identity_sync_runs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.identity_sync_runs (
    id uuid NOT NULL,
    database_epoch uuid NOT NULL,
    issuer text NOT NULL,
    expected_revision bigint NOT NULL,
    revision bigint NOT NULL,
    snapshot_sha256 text NOT NULL,
    captured_at timestamp with time zone NOT NULL,
    applied_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    counts jsonb NOT NULL,
    CONSTRAINT identity_sync_runs_check CHECK (((revision = (expected_revision + 1)) AND (revision <= '9007199254740991'::bigint))),
    CONSTRAINT identity_sync_runs_counts_check CHECK ((jsonb_typeof(counts) = 'object'::text)),
    CONSTRAINT identity_sync_runs_expected_revision_check CHECK ((expected_revision >= 0)),
    CONSTRAINT identity_sync_runs_snapshot_sha256_check CHECK ((snapshot_sha256 ~ '^[0-9a-f]{64}$'::text))
);


--
-- Name: TABLE identity_sync_runs; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.identity_sync_runs IS 'Offline trusted identity synchronization receipts; no token, subject list or membership snapshot is retained.';


--
-- Name: identity_sync_sources; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.identity_sync_sources (
    database_epoch uuid NOT NULL,
    issuer text NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    captured_at timestamp with time zone,
    operation_id uuid,
    CONSTRAINT identity_sync_sources_check CHECK ((((revision = 0) AND (captured_at IS NULL) AND (operation_id IS NULL)) OR ((revision > 0) AND (captured_at IS NOT NULL) AND (operation_id IS NOT NULL)))),
    CONSTRAINT identity_sync_sources_revision_check CHECK (((revision >= 0) AND (revision <= '9007199254740991'::bigint)))
);


--
-- Name: installation_challenges; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.installation_challenges (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    installation_id uuid NOT NULL,
    key_version bigint NOT NULL,
    challenge_sha256 bytea NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    consumed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT installation_challenges_challenge_sha256_check CHECK ((octet_length(challenge_sha256) = 32)),
    CONSTRAINT installation_challenges_check CHECK ((expires_at > created_at)),
    CONSTRAINT installation_challenges_key_version_check CHECK ((key_version > 0))
);


--
-- Name: TABLE installation_challenges; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.installation_challenges IS 'One-use installation-key challenges; stores only the challenge digest.';


--
-- Name: installations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.installations (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    host_id uuid NOT NULL,
    owner_user_id uuid NOT NULL,
    public_jwk jsonb NOT NULL,
    key_version bigint DEFAULT 1 NOT NULL,
    public_key_thumbprint text NOT NULL,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT installations_key_version_check CHECK ((key_version > 0)),
    CONSTRAINT installations_public_jwk_check CHECK ((jsonb_typeof(public_jwk) = 'object'::text))
);


--
-- Name: local_accounts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.local_accounts (
    user_id uuid NOT NULL,
    username text NOT NULL,
    password_hash text NOT NULL,
    password_version bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT local_accounts_password_version_check CHECK ((password_version > 0)),
    CONSTRAINT local_accounts_username_check CHECK ((username ~ '^[a-z0-9][a-z0-9_.-]{2,31}$'::text))
);


--
-- Name: org_memberships; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.org_memberships (
    org_id uuid NOT NULL,
    user_id uuid NOT NULL,
    role text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT org_memberships_role_check CHECK ((role = ANY (ARRAY['member'::text, 'admin'::text])))
);


--
-- Name: orgs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.orgs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    slug text NOT NULL,
    name text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    enrollment_admin_approval_required boolean DEFAULT false NOT NULL
);


--
-- Name: relay_runtime_routes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.relay_runtime_routes (
    runtime_id uuid NOT NULL,
    node_id uuid NOT NULL,
    generation uuid NOT NULL,
    runtime_epoch uuid NOT NULL,
    connection_id uuid NOT NULL
);


--
-- Name: TABLE relay_runtime_routes; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.relay_runtime_routes IS 'Rebuildable connection directory, never command acceptance or authorization. Compare node generation, epoch and connection before use or deletion.';


--
-- Name: remote_recovery_memberships; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.remote_recovery_memberships (
    recovery_id uuid NOT NULL,
    org_id uuid NOT NULL,
    user_id uuid NOT NULL,
    role text NOT NULL
);


--
-- Name: remote_recovery_runs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.remote_recovery_runs (
    id uuid NOT NULL,
    source_epoch uuid NOT NULL,
    target_epoch uuid NOT NULL,
    phase text NOT NULL,
    prepared_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    released_at timestamp with time zone,
    authority_sha256 text,
    counts jsonb NOT NULL,
    CONSTRAINT remote_recovery_runs_authority_sha256_check CHECK ((authority_sha256 ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT remote_recovery_runs_check CHECK ((target_epoch <> source_epoch)),
    CONSTRAINT remote_recovery_runs_check1 CHECK (((phase = 'ready'::text) = ((released_at IS NOT NULL) AND (authority_sha256 IS NOT NULL)))),
    CONSTRAINT remote_recovery_runs_counts_check CHECK ((jsonb_typeof(counts) = 'object'::text)),
    CONSTRAINT remote_recovery_runs_phase_check CHECK ((phase = ANY (ARRAY['quarantined'::text, 'ready'::text])))
);


--
-- Name: remote_recovery_state; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.remote_recovery_state (
    slot integer NOT NULL,
    epoch uuid NOT NULL,
    phase text NOT NULL,
    recovery_id uuid,
    token_not_before timestamp with time zone,
    CONSTRAINT remote_recovery_state_check CHECK (((phase = 'ready'::text) OR (recovery_id IS NOT NULL))),
    CONSTRAINT remote_recovery_state_phase_check CHECK ((phase = ANY (ARRAY['ready'::text, 'quarantined'::text]))),
    CONSTRAINT remote_recovery_state_slot_check CHECK ((slot = 1))
);


--
-- Name: runtime_grants; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.runtime_grants (
    runtime_id uuid NOT NULL,
    user_id uuid NOT NULL,
    role text NOT NULL,
    bindings jsonb CONSTRAINT runtime_grants_scope_not_null NOT NULL,
    grant_version bigint DEFAULT 1 NOT NULL,
    granted_by uuid NOT NULL,
    expires_at timestamp with time zone,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT runtime_grants_bindings_check CHECK ((jsonb_typeof(bindings) = 'array'::text)),
    CONSTRAINT runtime_grants_grant_version_check CHECK ((grant_version > 0)),
    CONSTRAINT runtime_grants_role_check CHECK ((role = ANY (ARRAY['viewer'::text, 'operator'::text, 'owner'::text])))
);


--
-- Name: COLUMN runtime_grants.bindings; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.runtime_grants.bindings IS 'Explicit JSON object: actions, workspaces and sessions arrays; empty arrays grant no access.';


--
-- Name: runtimes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.runtimes (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    installation_id uuid NOT NULL,
    profile_ref text NOT NULL,
    epoch uuid,
    dsh_version text,
    application_version text,
    plugin_version text,
    protocol_version integer DEFAULT 1 NOT NULL,
    last_seen_at timestamp with time zone,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    connection_id uuid,
    connection_key_version bigint,
    CONSTRAINT runtime_connection_key_version_consistency CHECK ((((connection_id IS NULL) AND (connection_key_version IS NULL)) OR ((connection_id IS NOT NULL) AND (connection_key_version > 0)))),
    CONSTRAINT runtimes_profile_ref_check CHECK (((length(profile_ref) >= 1) AND (length(profile_ref) <= 512))),
    CONSTRAINT runtimes_protocol_version_check CHECK ((protocol_version > 0))
);


--
-- Name: schema_identity; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.schema_identity (
    format text NOT NULL
);


--
-- Name: tunnel_leases; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tunnel_leases (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    device_id uuid NOT NULL,
    user_id uuid NOT NULL,
    runtime_id uuid NOT NULL,
    installation_id uuid NOT NULL,
    device_key_version bigint NOT NULL,
    grant_version bigint NOT NULL,
    secret_sha256 bytea NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone,
    CONSTRAINT tunnel_leases_check CHECK ((expires_at > created_at)),
    CONSTRAINT tunnel_leases_device_key_version_check CHECK ((device_key_version > 0)),
    CONSTRAINT tunnel_leases_grant_version_check CHECK ((grant_version > 0)),
    CONSTRAINT tunnel_leases_secret_sha256_check CHECK ((octet_length(secret_sha256) = 32))
);


--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    issuer text NOT NULL,
    subject text NOT NULL,
    status text DEFAULT 'active'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT users_status_check CHECK ((status = ANY (ARRAY['active'::text, 'suspended'::text, 'deleted'::text])))
);


--
-- Name: workspaces; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.workspaces (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    runtime_id uuid NOT NULL,
    workspace_ref text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT workspaces_workspace_ref_check CHECK (((length(workspace_ref) >= 1) AND (length(workspace_ref) <= 512)))
);


--
-- Name: COLUMN workspaces.workspace_ref; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.workspaces.workspace_ref IS 'Opaque runtime-owned workspace identity; never a host filesystem path.';


--
-- Name: account_login_limits account_login_limits_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.account_login_limits
    ADD CONSTRAINT account_login_limits_pkey PRIMARY KEY (bucket_hash);


--
-- Name: account_sessions account_sessions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.account_sessions
    ADD CONSTRAINT account_sessions_pkey PRIMARY KEY (token_hash);


--
-- Name: app_release_artifacts app_release_artifacts_package_name_version_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_release_artifacts
    ADD CONSTRAINT app_release_artifacts_package_name_version_code_key UNIQUE (package_name, version_code);


--
-- Name: app_release_artifacts app_release_artifacts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_release_artifacts
    ADD CONSTRAINT app_release_artifacts_pkey PRIMARY KEY (release_id);


--
-- Name: app_release_policies app_release_policies_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_release_policies
    ADD CONSTRAINT app_release_policies_pkey PRIMARY KEY (package_name);


--
-- Name: app_release_policy_changes app_release_policy_changes_package_name_revision_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_release_policy_changes
    ADD CONSTRAINT app_release_policy_changes_package_name_revision_key UNIQUE (package_name, revision);


--
-- Name: app_release_policy_changes app_release_policy_changes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_release_policy_changes
    ADD CONSTRAINT app_release_policy_changes_pkey PRIMARY KEY (operation_id);


--
-- Name: app_release_policy_confirmations app_release_policy_confirmation_package_name_database_epoch_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_release_policy_confirmations
    ADD CONSTRAINT app_release_policy_confirmation_package_name_database_epoch_key UNIQUE (package_name, database_epoch);


--
-- Name: app_release_policy_confirmations app_release_policy_confirmations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_release_policy_confirmations
    ADD CONSTRAINT app_release_policy_confirmations_pkey PRIMARY KEY (operation_id);


--
-- Name: app_releases app_releases_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_releases
    ADD CONSTRAINT app_releases_pkey PRIMARY KEY (id);


--
-- Name: app_releases app_releases_platform_version_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_releases
    ADD CONSTRAINT app_releases_platform_version_code_key UNIQUE (platform, version_code);


--
-- Name: audit_log audit_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_log
    ADD CONSTRAINT audit_log_pkey PRIMARY KEY (id);


--
-- Name: control_consumers control_consumers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.control_consumers
    ADD CONSTRAINT control_consumers_pkey PRIMARY KEY (node_id);


--
-- Name: control_event_acks control_event_acks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.control_event_acks
    ADD CONSTRAINT control_event_acks_pkey PRIMARY KEY (node_id, event_id);


--
-- Name: control_outbox control_outbox_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.control_outbox
    ADD CONSTRAINT control_outbox_pkey PRIMARY KEY (id);


--
-- Name: devices devices_approval_key_thumbprint_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.devices
    ADD CONSTRAINT devices_approval_key_thumbprint_key UNIQUE (approval_key_thumbprint);


--
-- Name: devices devices_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.devices
    ADD CONSTRAINT devices_pkey PRIMARY KEY (id);


--
-- Name: devices devices_proof_key_thumbprint_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.devices
    ADD CONSTRAINT devices_proof_key_thumbprint_key UNIQUE (proof_key_thumbprint);


--
-- Name: devices devices_user_id_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.devices
    ADD CONSTRAINT devices_user_id_id_key UNIQUE (user_id, id);


--
-- Name: dpop_proof_buckets dpop_proof_buckets_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dpop_proof_buckets
    ADD CONSTRAINT dpop_proof_buckets_pkey PRIMARY KEY (scope_sha256, bucket);


--
-- Name: dpop_proof_entries dpop_proof_entries_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dpop_proof_entries
    ADD CONSTRAINT dpop_proof_entries_pkey PRIMARY KEY (scope_sha256, bucket, kind, entry_sha256);


--
-- Name: dpop_proof_resources dpop_proof_resources_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dpop_proof_resources
    ADD CONSTRAINT dpop_proof_resources_pkey PRIMARY KEY (scope_sha256);


--
-- Name: enrollment_admin_denials enrollment_admin_denials_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.enrollment_admin_denials
    ADD CONSTRAINT enrollment_admin_denials_pkey PRIMARY KEY (enrollment_id);


--
-- Name: enrollment_terminal_checks enrollment_terminal_checks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.enrollment_terminal_checks
    ADD CONSTRAINT enrollment_terminal_checks_pkey PRIMARY KEY (enrollment_id);


--
-- Name: enrollments enrollments_code_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.enrollments
    ADD CONSTRAINT enrollments_code_hash_key UNIQUE (code_hash);


--
-- Name: enrollments enrollments_owner_request_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.enrollments
    ADD CONSTRAINT enrollments_owner_request_unique UNIQUE (requested_by, request_id);


--
-- Name: enrollments enrollments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.enrollments
    ADD CONSTRAINT enrollments_pkey PRIMARY KEY (id);


--
-- Name: host_connection_credentials host_connection_credentials_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.host_connection_credentials
    ADD CONSTRAINT host_connection_credentials_pkey PRIMARY KEY (token_sha256);


--
-- Name: hosts hosts_org_id_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.hosts
    ADD CONSTRAINT hosts_org_id_id_key UNIQUE (org_id, id);


--
-- Name: hosts hosts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.hosts
    ADD CONSTRAINT hosts_pkey PRIMARY KEY (id);


--
-- Name: identity_sync_runs identity_sync_runs_database_epoch_issuer_revision_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.identity_sync_runs
    ADD CONSTRAINT identity_sync_runs_database_epoch_issuer_revision_key UNIQUE (database_epoch, issuer, revision);


--
-- Name: identity_sync_runs identity_sync_runs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.identity_sync_runs
    ADD CONSTRAINT identity_sync_runs_pkey PRIMARY KEY (id);


--
-- Name: identity_sync_sources identity_sync_sources_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.identity_sync_sources
    ADD CONSTRAINT identity_sync_sources_pkey PRIMARY KEY (database_epoch, issuer);


--
-- Name: installation_challenges installation_challenges_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.installation_challenges
    ADD CONSTRAINT installation_challenges_pkey PRIMARY KEY (id);


--
-- Name: installations installations_host_id_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.installations
    ADD CONSTRAINT installations_host_id_id_key UNIQUE (host_id, id);


--
-- Name: installations installations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.installations
    ADD CONSTRAINT installations_pkey PRIMARY KEY (id);


--
-- Name: installations installations_public_key_thumbprint_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.installations
    ADD CONSTRAINT installations_public_key_thumbprint_key UNIQUE (public_key_thumbprint);


--
-- Name: local_accounts local_accounts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.local_accounts
    ADD CONSTRAINT local_accounts_pkey PRIMARY KEY (user_id);


--
-- Name: local_accounts local_accounts_username_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.local_accounts
    ADD CONSTRAINT local_accounts_username_key UNIQUE (username);


--
-- Name: org_memberships org_memberships_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.org_memberships
    ADD CONSTRAINT org_memberships_pkey PRIMARY KEY (org_id, user_id);


--
-- Name: orgs orgs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orgs
    ADD CONSTRAINT orgs_pkey PRIMARY KEY (id);


--
-- Name: orgs orgs_slug_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orgs
    ADD CONSTRAINT orgs_slug_key UNIQUE (slug);


--
-- Name: relay_runtime_routes relay_runtime_routes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_runtime_routes
    ADD CONSTRAINT relay_runtime_routes_pkey PRIMARY KEY (runtime_id);


--
-- Name: remote_recovery_memberships remote_recovery_memberships_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.remote_recovery_memberships
    ADD CONSTRAINT remote_recovery_memberships_pkey PRIMARY KEY (recovery_id, org_id, user_id);


--
-- Name: remote_recovery_runs remote_recovery_runs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.remote_recovery_runs
    ADD CONSTRAINT remote_recovery_runs_pkey PRIMARY KEY (id);


--
-- Name: remote_recovery_runs remote_recovery_runs_target_epoch_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.remote_recovery_runs
    ADD CONSTRAINT remote_recovery_runs_target_epoch_key UNIQUE (target_epoch);


--
-- Name: remote_recovery_state remote_recovery_state_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.remote_recovery_state
    ADD CONSTRAINT remote_recovery_state_pkey PRIMARY KEY (slot);


--
-- Name: runtime_grants runtime_grants_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.runtime_grants
    ADD CONSTRAINT runtime_grants_pkey PRIMARY KEY (runtime_id, user_id);


--
-- Name: runtimes runtimes_installation_id_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.runtimes
    ADD CONSTRAINT runtimes_installation_id_id_key UNIQUE (installation_id, id);


--
-- Name: runtimes runtimes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.runtimes
    ADD CONSTRAINT runtimes_pkey PRIMARY KEY (id);


--
-- Name: schema_identity schema_identity_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.schema_identity
    ADD CONSTRAINT schema_identity_pkey PRIMARY KEY (format);


--
-- Name: tunnel_leases tunnel_leases_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tunnel_leases
    ADD CONSTRAINT tunnel_leases_pkey PRIMARY KEY (id);


--
-- Name: users users_issuer_subject_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_issuer_subject_key UNIQUE (issuer, subject);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- Name: workspaces workspaces_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workspaces
    ADD CONSTRAINT workspaces_pkey PRIMARY KEY (id);


--
-- Name: workspaces workspaces_runtime_id_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workspaces
    ADD CONSTRAINT workspaces_runtime_id_id_key UNIQUE (runtime_id, id);


--
-- Name: workspaces workspaces_runtime_id_workspace_ref_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workspaces
    ADD CONSTRAINT workspaces_runtime_id_workspace_ref_key UNIQUE (runtime_id, workspace_ref);


--
-- Name: account_sessions_expiry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX account_sessions_expiry ON public.account_sessions USING btree (expires_at);


--
-- Name: account_sessions_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX account_sessions_user ON public.account_sessions USING btree (user_id, created_at);


--
-- Name: audit_log_archive_candidates; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX audit_log_archive_candidates ON public.audit_log USING btree (ts, id);


--
-- Name: audit_org_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX audit_org_time ON public.audit_log USING btree (org_id, ts DESC);


--
-- Name: control_consumers_ephemeral_expiry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX control_consumers_ephemeral_expiry ON public.control_consumers USING btree (lease_until) WHERE (NOT durable);


--
-- Name: control_outbox_epoch_producer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX control_outbox_epoch_producer ON public.control_outbox USING btree (database_epoch, producer_xid, created_at, id);


--
-- Name: control_outbox_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX control_outbox_pending ON public.control_outbox USING btree (created_at, id) WHERE (published_at IS NULL);


--
-- Name: control_outbox_retention; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX control_outbox_retention ON public.control_outbox USING btree (created_at, id) WHERE (topic = ANY (ARRAY['device.revoked'::text, 'runtime.revoked'::text, 'runtime_grant.updated'::text, 'runtime_grant.revoked'::text, 'approval.pending'::text, 'approval.decided'::text, 'approval.ended'::text, 'approval.result'::text, 'approval.authorization.prepared'::text, 'approval.device.revoked'::text]));


--
-- Name: devices_owner_directory; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX devices_owner_directory ON public.devices USING btree (user_id, created_at DESC, id DESC);


--
-- Name: dpop_proof_cleanup_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX dpop_proof_cleanup_order ON public.dpop_proof_buckets USING btree (scope_sha256, last_cleanup_at, bucket);


--
-- Name: dpop_proof_expiry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX dpop_proof_expiry ON public.dpop_proof_entries USING btree (scope_sha256, bucket, expires_at);


--
-- Name: enrollments_admin_directory; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX enrollments_admin_directory ON public.enrollments USING btree (host_id, expires_at, created_at, id) WHERE (admin_approval_required AND (state = ANY (ARRAY['pending'::text, 'user_confirmed'::text])));


--
-- Name: enrollments_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX enrollments_pending ON public.enrollments USING btree (expires_at) WHERE (state = ANY (ARRAY['pending'::text, 'user_confirmed'::text]));


--
-- Name: enrollments_unapproved_runtime_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX enrollments_unapproved_runtime_idx ON public.enrollments USING btree (runtime_id) WHERE (state <> 'approved'::text);


--
-- Name: host_connection_credentials_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX host_connection_credentials_active ON public.host_connection_credentials USING btree (installation_id, expires_at) WHERE (revoked_at IS NULL);


--
-- Name: host_connection_credentials_installation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX host_connection_credentials_installation ON public.host_connection_credentials USING btree (installation_id);


--
-- Name: installation_challenges_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX installation_challenges_pending ON public.installation_challenges USING btree (installation_id, expires_at) WHERE (consumed_at IS NULL);


--
-- Name: installation_challenges_recent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX installation_challenges_recent ON public.installation_challenges USING btree (installation_id, created_at DESC);


--
-- Name: installations_owner_directory; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX installations_owner_directory ON public.installations USING btree (owner_user_id, created_at DESC, id DESC);


--
-- Name: relay_runtime_routes_node; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX relay_runtime_routes_node ON public.relay_runtime_routes USING btree (node_id, generation);


--
-- Name: runtime_grants_user_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX runtime_grants_user_active ON public.runtime_grants USING btree (user_id, runtime_id) WHERE (revoked_at IS NULL);


--
-- Name: runtimes_connection_id_unique; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX runtimes_connection_id_unique ON public.runtimes USING btree (connection_id) WHERE (connection_id IS NOT NULL);


--
-- Name: runtimes_live_installation_profile; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX runtimes_live_installation_profile ON public.runtimes USING btree (installation_id, profile_ref) WHERE (revoked_at IS NULL);


--
-- Name: tunnel_leases_active_device; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX tunnel_leases_active_device ON public.tunnel_leases USING btree (device_id, runtime_id) WHERE (revoked_at IS NULL);


--
-- Name: tunnel_leases_expiry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX tunnel_leases_expiry ON public.tunnel_leases USING btree (expires_at) WHERE (revoked_at IS NULL);


--
-- Name: app_release_artifacts app_release_artifact_audit; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER app_release_artifact_audit AFTER INSERT ON public.app_release_artifacts FOR EACH ROW EXECUTE FUNCTION public.app_release_artifact_guard();


--
-- Name: app_release_artifacts app_release_artifact_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER app_release_artifact_guard BEFORE INSERT OR DELETE OR UPDATE ON public.app_release_artifacts FOR EACH ROW EXECUTE FUNCTION public.app_release_artifact_guard();


--
-- Name: app_release_artifacts app_release_artifact_no_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER app_release_artifact_no_truncate BEFORE TRUNCATE ON public.app_release_artifacts FOR EACH STATEMENT EXECUTE FUNCTION public.app_release_artifact_guard();


--
-- Name: app_release_policies app_release_policy_audit; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER app_release_policy_audit AFTER INSERT ON public.app_release_policies FOR EACH ROW EXECUTE FUNCTION public.app_release_policy_guard();


--
-- Name: app_release_policy_changes app_release_policy_change_apply; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER app_release_policy_change_apply AFTER INSERT ON public.app_release_policy_changes FOR EACH ROW EXECUTE FUNCTION public.app_release_policy_change_guard();


--
-- Name: app_release_policy_changes app_release_policy_change_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER app_release_policy_change_guard BEFORE INSERT OR DELETE OR UPDATE ON public.app_release_policy_changes FOR EACH ROW EXECUTE FUNCTION public.app_release_policy_change_guard();


--
-- Name: app_release_policy_changes app_release_policy_change_no_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER app_release_policy_change_no_truncate BEFORE TRUNCATE ON public.app_release_policy_changes FOR EACH STATEMENT EXECUTE FUNCTION public.app_release_policy_change_guard();


--
-- Name: app_release_policy_confirmations app_release_policy_confirmation_apply; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER app_release_policy_confirmation_apply AFTER INSERT ON public.app_release_policy_confirmations FOR EACH ROW EXECUTE FUNCTION public.app_release_policy_confirmation_guard();


--
-- Name: app_release_policy_confirmations app_release_policy_confirmation_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER app_release_policy_confirmation_guard BEFORE INSERT OR DELETE OR UPDATE ON public.app_release_policy_confirmations FOR EACH ROW EXECUTE FUNCTION public.app_release_policy_confirmation_guard();


--
-- Name: app_release_policy_confirmations app_release_policy_confirmation_no_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER app_release_policy_confirmation_no_truncate BEFORE TRUNCATE ON public.app_release_policy_confirmations FOR EACH STATEMENT EXECUTE FUNCTION public.app_release_policy_confirmation_guard();


--
-- Name: app_release_policies app_release_policy_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER app_release_policy_guard BEFORE INSERT OR DELETE OR UPDATE ON public.app_release_policies FOR EACH ROW EXECUTE FUNCTION public.app_release_policy_guard();


--
-- Name: app_release_policies app_release_policy_no_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER app_release_policy_no_truncate BEFORE TRUNCATE ON public.app_release_policies FOR EACH STATEMENT EXECUTE FUNCTION public.app_release_policy_guard();


--
-- Name: audit_log audit_log_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER audit_log_immutable BEFORE DELETE OR UPDATE ON public.audit_log FOR EACH ROW EXECUTE FUNCTION public.audit_log_immutable();


--
-- Name: audit_log audit_log_no_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER audit_log_no_truncate BEFORE TRUNCATE ON public.audit_log FOR EACH STATEMENT EXECUTE FUNCTION public.control_table_no_truncate();


--
-- Name: audit_log audit_log_stamp; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER audit_log_stamp BEFORE INSERT ON public.audit_log FOR EACH ROW EXECUTE FUNCTION public.audit_log_stamp();


--
-- Name: control_event_acks control_ack_no_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER control_ack_no_truncate BEFORE TRUNCATE ON public.control_event_acks FOR EACH STATEMENT EXECUTE FUNCTION public.control_table_no_truncate();


--
-- Name: control_consumers control_consumer_no_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER control_consumer_no_truncate BEFORE TRUNCATE ON public.control_consumers FOR EACH STATEMENT EXECUTE FUNCTION public.control_table_no_truncate();


--
-- Name: control_event_acks control_hot_ack_retention; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER control_hot_ack_retention AFTER DELETE ON public.control_event_acks REFERENCING OLD TABLE AS deleted_control_acks FOR EACH STATEMENT EXECUTE FUNCTION public.control_hot_ack_retention_guard();


--
-- Name: control_outbox control_outbox_identity; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER control_outbox_identity BEFORE INSERT OR UPDATE ON public.control_outbox FOR EACH ROW EXECUTE FUNCTION public.control_outbox_identity();


--
-- Name: control_outbox control_outbox_no_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER control_outbox_no_truncate BEFORE TRUNCATE ON public.control_outbox FOR EACH STATEMENT EXECUTE FUNCTION public.control_table_no_truncate();


--
-- Name: enrollment_admin_denials enrollment_admin_denial_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER enrollment_admin_denial_immutable BEFORE DELETE OR UPDATE ON public.enrollment_admin_denials FOR EACH ROW EXECUTE FUNCTION public.enrollment_admin_denial_immutable();


--
-- Name: enrollment_admin_denials enrollment_admin_denial_no_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER enrollment_admin_denial_no_truncate BEFORE TRUNCATE ON public.enrollment_admin_denials FOR EACH STATEMENT EXECUTE FUNCTION public.enrollment_admin_denial_immutable();


--
-- Name: enrollment_admin_denials enrollment_admin_denial_owner; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER enrollment_admin_denial_owner BEFORE INSERT ON public.enrollment_admin_denials FOR EACH ROW EXECUTE FUNCTION public.enrollment_admin_denial_owner();


--
-- Name: enrollments enrollment_admin_denial_terminal; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER enrollment_admin_denial_terminal BEFORE UPDATE ON public.enrollments FOR EACH ROW WHEN ((old.state = 'denied'::text)) EXECUTE FUNCTION public.enrollment_admin_denial_terminal();


--
-- Name: enrollments enrollment_admin_gate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER enrollment_admin_gate BEFORE INSERT OR UPDATE ON public.enrollments FOR EACH ROW EXECUTE FUNCTION public.enrollment_admin_gate();


--
-- Name: orgs enrollment_admin_policy_tighten; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER enrollment_admin_policy_tighten AFTER UPDATE OF enrollment_admin_approval_required ON public.orgs FOR EACH ROW EXECUTE FUNCTION public.enrollment_admin_policy_tighten();


--
-- Name: enrollments enrollment_observed_terminal; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER enrollment_observed_terminal BEFORE UPDATE ON public.enrollments FOR EACH ROW WHEN ((old.state = ANY (ARRAY['denied'::text, 'expired'::text, 'cancelled'::text]))) EXECUTE FUNCTION public.enrollment_observed_terminal();


--
-- Name: enrollment_terminal_checks enrollment_terminal_check_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER enrollment_terminal_check_immutable BEFORE DELETE OR UPDATE ON public.enrollment_terminal_checks FOR EACH ROW EXECUTE FUNCTION public.enrollment_terminal_check_immutable();


--
-- Name: enrollment_terminal_checks enrollment_terminal_check_no_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER enrollment_terminal_check_no_truncate BEFORE TRUNCATE ON public.enrollment_terminal_checks FOR EACH STATEMENT EXECUTE FUNCTION public.enrollment_terminal_check_immutable();


--
-- Name: enrollment_terminal_checks enrollment_terminal_check_owner; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER enrollment_terminal_check_owner BEFORE INSERT ON public.enrollment_terminal_checks FOR EACH ROW EXECUTE FUNCTION public.enrollment_terminal_check_owner();


--
-- Name: users revoke_inactive_account_sessions; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER revoke_inactive_account_sessions AFTER UPDATE OF status ON public.users FOR EACH ROW WHEN ((new.status IS DISTINCT FROM old.status)) EXECUTE FUNCTION public.revoke_inactive_account_sessions();


--
-- Name: account_sessions account_sessions_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.account_sessions
    ADD CONSTRAINT account_sessions_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.local_accounts(user_id);


--
-- Name: app_release_policy_changes app_release_policy_changes_package_name_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_release_policy_changes
    ADD CONSTRAINT app_release_policy_changes_package_name_fkey FOREIGN KEY (package_name) REFERENCES public.app_release_policies(package_name);


--
-- Name: app_release_policy_confirmations app_release_policy_confirmations_package_name_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_release_policy_confirmations
    ADD CONSTRAINT app_release_policy_confirmations_package_name_fkey FOREIGN KEY (package_name) REFERENCES public.app_release_policies(package_name);


--
-- Name: audit_log audit_log_device_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_log
    ADD CONSTRAINT audit_log_device_id_fkey FOREIGN KEY (device_id) REFERENCES public.devices(id);


--
-- Name: audit_log audit_log_org_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_log
    ADD CONSTRAINT audit_log_org_id_fkey FOREIGN KEY (org_id) REFERENCES public.orgs(id);


--
-- Name: audit_log audit_log_runtime_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_log
    ADD CONSTRAINT audit_log_runtime_id_fkey FOREIGN KEY (runtime_id) REFERENCES public.runtimes(id);


--
-- Name: control_event_acks control_event_acks_event_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.control_event_acks
    ADD CONSTRAINT control_event_acks_event_id_fkey FOREIGN KEY (event_id) REFERENCES public.control_outbox(id) ON DELETE CASCADE;


--
-- Name: control_event_acks control_event_acks_node_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.control_event_acks
    ADD CONSTRAINT control_event_acks_node_id_fkey FOREIGN KEY (node_id) REFERENCES public.control_consumers(node_id) ON DELETE CASCADE;


--
-- Name: control_outbox control_outbox_org_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.control_outbox
    ADD CONSTRAINT control_outbox_org_id_fkey FOREIGN KEY (org_id) REFERENCES public.orgs(id);


--
-- Name: devices devices_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.devices
    ADD CONSTRAINT devices_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: dpop_proof_buckets dpop_proof_buckets_scope_sha256_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dpop_proof_buckets
    ADD CONSTRAINT dpop_proof_buckets_scope_sha256_fkey FOREIGN KEY (scope_sha256) REFERENCES public.dpop_proof_resources(scope_sha256);


--
-- Name: dpop_proof_entries dpop_proof_entries_scope_sha256_bucket_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dpop_proof_entries
    ADD CONSTRAINT dpop_proof_entries_scope_sha256_bucket_fkey FOREIGN KEY (scope_sha256, bucket) REFERENCES public.dpop_proof_buckets(scope_sha256, bucket);


--
-- Name: enrollment_admin_denials enrollment_admin_denials_denied_by_device_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.enrollment_admin_denials
    ADD CONSTRAINT enrollment_admin_denials_denied_by_device_id_fkey FOREIGN KEY (denied_by, device_id) REFERENCES public.devices(user_id, id);


--
-- Name: enrollment_admin_denials enrollment_admin_denials_denied_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.enrollment_admin_denials
    ADD CONSTRAINT enrollment_admin_denials_denied_by_fkey FOREIGN KEY (denied_by) REFERENCES public.users(id);


--
-- Name: enrollment_admin_denials enrollment_admin_denials_enrollment_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.enrollment_admin_denials
    ADD CONSTRAINT enrollment_admin_denials_enrollment_id_fkey FOREIGN KEY (enrollment_id) REFERENCES public.enrollments(id);


--
-- Name: enrollments enrollment_admin_device_owner; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.enrollments
    ADD CONSTRAINT enrollment_admin_device_owner FOREIGN KEY (admin_approved_by, admin_approved_device_id) REFERENCES public.devices(user_id, id);


--
-- Name: enrollments enrollment_confirmation_device_owner; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.enrollments
    ADD CONSTRAINT enrollment_confirmation_device_owner FOREIGN KEY (user_confirmed_by, user_confirmed_device_id) REFERENCES public.devices(user_id, id);


--
-- Name: enrollment_terminal_checks enrollment_terminal_checks_enrollment_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.enrollment_terminal_checks
    ADD CONSTRAINT enrollment_terminal_checks_enrollment_id_fkey FOREIGN KEY (enrollment_id) REFERENCES public.enrollments(id);


--
-- Name: enrollment_terminal_checks enrollment_terminal_checks_owner_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.enrollment_terminal_checks
    ADD CONSTRAINT enrollment_terminal_checks_owner_user_id_fkey FOREIGN KEY (owner_user_id) REFERENCES public.users(id);


--
-- Name: enrollments enrollments_admin_approved_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.enrollments
    ADD CONSTRAINT enrollments_admin_approved_by_fkey FOREIGN KEY (admin_approved_by) REFERENCES public.users(id);


--
-- Name: enrollments enrollments_decided_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.enrollments
    ADD CONSTRAINT enrollments_decided_by_fkey FOREIGN KEY (decided_by) REFERENCES public.users(id);


--
-- Name: enrollments enrollments_host_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.enrollments
    ADD CONSTRAINT enrollments_host_id_fkey FOREIGN KEY (host_id) REFERENCES public.hosts(id);


--
-- Name: enrollments enrollments_installation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.enrollments
    ADD CONSTRAINT enrollments_installation_id_fkey FOREIGN KEY (installation_id) REFERENCES public.installations(id);


--
-- Name: enrollments enrollments_installation_runtime_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.enrollments
    ADD CONSTRAINT enrollments_installation_runtime_fk FOREIGN KEY (installation_id, runtime_id) REFERENCES public.runtimes(installation_id, id);


--
-- Name: enrollments enrollments_requested_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.enrollments
    ADD CONSTRAINT enrollments_requested_by_fkey FOREIGN KEY (requested_by) REFERENCES public.users(id);


--
-- Name: enrollments enrollments_user_confirmed_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.enrollments
    ADD CONSTRAINT enrollments_user_confirmed_by_fkey FOREIGN KEY (user_confirmed_by) REFERENCES public.users(id);


--
-- Name: host_connection_credentials host_connection_credentials_installation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.host_connection_credentials
    ADD CONSTRAINT host_connection_credentials_installation_id_fkey FOREIGN KEY (installation_id) REFERENCES public.installations(id) ON DELETE CASCADE;


--
-- Name: hosts hosts_org_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.hosts
    ADD CONSTRAINT hosts_org_id_fkey FOREIGN KEY (org_id) REFERENCES public.orgs(id);


--
-- Name: identity_sync_sources identity_sync_sources_operation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.identity_sync_sources
    ADD CONSTRAINT identity_sync_sources_operation_id_fkey FOREIGN KEY (operation_id) REFERENCES public.identity_sync_runs(id);


--
-- Name: installation_challenges installation_challenges_installation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.installation_challenges
    ADD CONSTRAINT installation_challenges_installation_id_fkey FOREIGN KEY (installation_id) REFERENCES public.installations(id) ON DELETE CASCADE;


--
-- Name: installations installations_host_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.installations
    ADD CONSTRAINT installations_host_id_fkey FOREIGN KEY (host_id) REFERENCES public.hosts(id);


--
-- Name: installations installations_owner_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.installations
    ADD CONSTRAINT installations_owner_user_id_fkey FOREIGN KEY (owner_user_id) REFERENCES public.users(id);


--
-- Name: local_accounts local_accounts_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.local_accounts
    ADD CONSTRAINT local_accounts_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: org_memberships org_memberships_org_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.org_memberships
    ADD CONSTRAINT org_memberships_org_id_fkey FOREIGN KEY (org_id) REFERENCES public.orgs(id);


--
-- Name: org_memberships org_memberships_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.org_memberships
    ADD CONSTRAINT org_memberships_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: relay_runtime_routes relay_runtime_routes_node_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_runtime_routes
    ADD CONSTRAINT relay_runtime_routes_node_id_fkey FOREIGN KEY (node_id) REFERENCES public.control_consumers(node_id) ON DELETE CASCADE;


--
-- Name: relay_runtime_routes relay_runtime_routes_runtime_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_runtime_routes
    ADD CONSTRAINT relay_runtime_routes_runtime_id_fkey FOREIGN KEY (runtime_id) REFERENCES public.runtimes(id) ON DELETE CASCADE;


--
-- Name: remote_recovery_memberships remote_recovery_memberships_recovery_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.remote_recovery_memberships
    ADD CONSTRAINT remote_recovery_memberships_recovery_id_fkey FOREIGN KEY (recovery_id) REFERENCES public.remote_recovery_runs(id);


--
-- Name: remote_recovery_state remote_recovery_state_recovery_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.remote_recovery_state
    ADD CONSTRAINT remote_recovery_state_recovery_id_fkey FOREIGN KEY (recovery_id) REFERENCES public.remote_recovery_runs(id);


--
-- Name: runtime_grants runtime_grants_granted_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.runtime_grants
    ADD CONSTRAINT runtime_grants_granted_by_fkey FOREIGN KEY (granted_by) REFERENCES public.users(id);


--
-- Name: runtime_grants runtime_grants_runtime_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.runtime_grants
    ADD CONSTRAINT runtime_grants_runtime_id_fkey FOREIGN KEY (runtime_id) REFERENCES public.runtimes(id);


--
-- Name: runtime_grants runtime_grants_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.runtime_grants
    ADD CONSTRAINT runtime_grants_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: runtimes runtimes_installation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.runtimes
    ADD CONSTRAINT runtimes_installation_id_fkey FOREIGN KEY (installation_id) REFERENCES public.installations(id);


--
-- Name: tunnel_leases tunnel_leases_device_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tunnel_leases
    ADD CONSTRAINT tunnel_leases_device_id_fkey FOREIGN KEY (device_id) REFERENCES public.devices(id);


--
-- Name: tunnel_leases tunnel_leases_installation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tunnel_leases
    ADD CONSTRAINT tunnel_leases_installation_id_fkey FOREIGN KEY (installation_id) REFERENCES public.installations(id);


--
-- Name: tunnel_leases tunnel_leases_runtime_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tunnel_leases
    ADD CONSTRAINT tunnel_leases_runtime_id_fkey FOREIGN KEY (runtime_id) REFERENCES public.runtimes(id);


--
-- Name: tunnel_leases tunnel_leases_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tunnel_leases
    ADD CONSTRAINT tunnel_leases_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: workspaces workspaces_runtime_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workspaces
    ADD CONSTRAINT workspaces_runtime_id_fkey FOREIGN KEY (runtime_id) REFERENCES public.runtimes(id);


--
-- PostgreSQL database dump complete
--

\unrestrict 5YdJvhqrWkFWquPuzJrCbRRJZ4be5HDplK2KVLQUjg9IrnDLLylTSOnRCOSfYFt


INSERT INTO public.schema_identity (format) VALUES ('tokenworks-mobile-native-transport/v1');

-- The relay refuses to start without one ready recovery slot; the epoch is
-- the published original, not a deployment-specific rotation.
INSERT INTO public.remote_recovery_state (slot, epoch, phase)
VALUES (1, '00000000-0000-4000-8000-000000000000', 'ready');
