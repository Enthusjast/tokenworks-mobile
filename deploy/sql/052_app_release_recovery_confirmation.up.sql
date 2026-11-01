begin;
-- Preserve the last explicit acceptance epoch, not the epoch of this migration.
alter table app_release_policies add column validated_epoch uuid;
alter table app_release_policies disable trigger app_release_policy_guard;
update app_release_policies set validated_epoch=changed_epoch;
alter table app_release_policies enable trigger app_release_policy_guard;
alter table app_release_policies alter column validated_epoch set not null;

create table app_release_policy_confirmations (
    operation_id uuid primary key,
    database_epoch uuid not null,
    package_name text not null references app_release_policies(package_name),
    policy_sha256 bytea not null check(octet_length(policy_sha256)=32),
    revision bigint not null check(revision>0),
    confirmed_at timestamptz not null,
    unique(package_name,database_epoch)
);
create or replace function app_release_policy_guard() returns trigger language plpgsql as $$
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

create function app_release_policy_confirmation_guard() returns trigger language plpgsql as $$
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
create trigger app_release_policy_confirmation_guard before insert or update or delete on app_release_policy_confirmations
    for each row execute function app_release_policy_confirmation_guard();
create trigger app_release_policy_confirmation_apply after insert on app_release_policy_confirmations
    for each row execute function app_release_policy_confirmation_guard();
create trigger app_release_policy_confirmation_no_truncate before truncate on app_release_policy_confirmations
    for each statement execute function app_release_policy_confirmation_guard();
commit;
