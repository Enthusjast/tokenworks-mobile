begin;
-- Append-only identity registry, independent of mutable publication/rollout policy.
-- The initial app_releases table is an unused legacy placeholder, not trusted input.
create table app_release_artifacts (
    release_id uuid primary key,
    package_name text not null check(length(package_name) between 3 and 255
        and package_name ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$'),
    version_code bigint not null check(version_code between 1 and 9007199254740991),
    version_name text not null check(octet_length(version_name) between 1 and 64),
    sha256 text not null check(sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes bigint not null check(size_bytes between 1 and 536870912),
    signing_cert_sha256 text not null check(signing_cert_sha256 ~ '^[0-9a-f]{64}$'),
    minimum_protocol_version bigint not null check(minimum_protocol_version between 1 and 32),
    maximum_protocol_version bigint not null check(maximum_protocol_version between minimum_protocol_version and 32),
    minimum_android_api bigint not null check(minimum_android_api between 26 and 100),
    identity_sha256 bytea not null check(octet_length(identity_sha256)=32),
    registered_epoch uuid not null,
    registered_at timestamptz not null,
    unique(package_name,version_code)
);

create function app_release_artifact_guard() returns trigger language plpgsql as $$
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
create trigger app_release_artifact_guard before insert or update or delete on app_release_artifacts
    for each row execute function app_release_artifact_guard();
create trigger app_release_artifact_audit after insert on app_release_artifacts
    for each row execute function app_release_artifact_guard();
create trigger app_release_artifact_no_truncate before truncate on app_release_artifacts
    for each statement execute function app_release_artifact_guard();
commit;
