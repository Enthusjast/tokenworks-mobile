begin;
lock table app_release_artifacts,app_release_policies,app_release_policy_confirmations in access exclusive mode;
do $$ begin
    if exists(select 1 from app_release_policy_confirmations) then
        raise exception 'release policy confirmations prevent destructive rollback';
    end if;
end $$;
drop table app_release_policy_confirmations;
drop function app_release_policy_confirmation_guard();
alter table app_release_policies drop column validated_epoch;
create or replace function app_release_policy_guard() returns trigger language plpgsql as $$
declare current_epoch uuid; current_phase text; audit_id bigint; recorded_time timestamptz;
begin
    if tg_op not in ('INSERT','UPDATE') then raise exception 'release policy history cannot be removed' using errcode='23514'; end if;
    select epoch,phase into current_epoch,current_phase from remote_recovery_state where slot=1 for share;
    if current_phase is distinct from 'ready' or current_epoch is distinct from new.changed_epoch then
        raise exception 'release policy recovery is fenced' using errcode='23514';
    end if;
    if tg_op='UPDATE' then
        if new.package_name<>old.package_name or new.revision<>old.revision+1 or new.policy_sha256=old.policy_sha256 then
            raise exception 'invalid release policy transition' using errcode='23514';
        end if;
        select changed_at into recorded_time from app_release_policy_changes
            where package_name=new.package_name and revision=new.revision and database_epoch=new.changed_epoch
                and previous_sha256=old.policy_sha256 and policy_sha256=new.policy_sha256;
        if not found then raise exception 'release policy requires retained operation' using errcode='23514'; end if;
        new.changed_at=recorded_time;
    elsif tg_when='BEFORE' then
        new.revision=1; new.changed_at=clock_timestamp();
    else
        insert into audit_log(actor_type,actor_id,action,result,metadata)
        values('system',current_user,'app.release.policy.register','success',
            jsonb_build_object('package_name',new.package_name,'policy_sha256',encode(new.policy_sha256,'hex'),
                'revision',new.revision,'database_epoch',new.changed_epoch)) returning id into audit_id;
        if audit_id is null then raise exception 'release policy audit is unavailable' using errcode='23514'; end if;
    end if;
    return new;
end $$;
commit;
