begin;
create table app_release_policies (
    package_name text primary key check(length(package_name) between 3 and 255
        and package_name ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$'),
    policy_sha256 bytea not null check(octet_length(policy_sha256)=32),
    revision bigint not null check(revision>0),
    changed_epoch uuid not null,
    changed_at timestamptz not null
);
create table app_release_policy_changes (
    operation_id uuid primary key,
    database_epoch uuid not null,
    package_name text not null references app_release_policies(package_name),
    previous_sha256 bytea not null check(octet_length(previous_sha256)=32),
    policy_sha256 bytea not null check(octet_length(policy_sha256)=32 and policy_sha256<>previous_sha256),
    revision bigint not null check(revision>1),
    changed_at timestamptz not null,
    unique(package_name,revision)
);
create function app_release_policy_guard() returns trigger language plpgsql as $$
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
create trigger app_release_policy_guard before insert or update or delete on app_release_policies
    for each row execute function app_release_policy_guard();
create trigger app_release_policy_audit after insert on app_release_policies
    for each row execute function app_release_policy_guard();
create trigger app_release_policy_no_truncate before truncate on app_release_policies
    for each statement execute function app_release_policy_guard();

create function app_release_policy_change_guard() returns trigger language plpgsql as $$
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
create trigger app_release_policy_change_guard before insert or update or delete on app_release_policy_changes
    for each row execute function app_release_policy_change_guard();
create trigger app_release_policy_change_apply after insert on app_release_policy_changes
    for each row execute function app_release_policy_change_guard();
create trigger app_release_policy_change_no_truncate before truncate on app_release_policy_changes
    for each statement execute function app_release_policy_change_guard();
commit;
