begin;

-- Existing identities must be fenced by the established recovery procedure.
-- No stored payload, signature, digest, issuer or username is rewritten here.
do $$
declare current_phase text;
begin
    select phase into current_phase from remote_recovery_state where slot=1 for update;
    if current_phase is distinct from 'quarantined'
       and (exists(select 1 from users) or exists(select 1 from hosts)
            or exists(select 1 from runtimes) or exists(select 1 from local_accounts)) then
        raise exception 'tokenworks-mobile identity cutover requires recovery quarantine' using errcode='23514';
    end if;
end $$;

create or replace function control_archive_import_payload_guard() returns trigger language plpgsql as $$
declare item control_archive_imports%rowtype; current_epoch uuid; current_phase text; ack_count bigint;
begin
    if tg_op='UPDATE' then raise exception 'archive import payload is immutable' using errcode='23514'; end if;
    select * into item from control_archive_imports where id=case when tg_op='INSERT' then new.import_id else old.import_id end;
    select epoch,phase into current_epoch,current_phase from remote_recovery_state where slot=1 for share;
    if item.id is null or current_phase is distinct from 'ready' then
        raise exception 'archive import is unavailable' using errcode='23514';
    end if;
    if tg_op='DELETE' then
        if item.retain_until>clock_timestamp() then raise exception 'archive import retention has not elapsed' using errcode='23514'; end if;
        insert into audit_log(actor_type,actor_id,action,result,metadata)
        values('system',current_user,'control.archive.import.delete','success',
            jsonb_build_object('import_id',item.id,'domain_id',item.domain_id,'export_id',item.export_id,'current_epoch',current_epoch,'db_session_user',session_user));
        return old;
    end if;
    if item.import_xid<>pg_current_xact_id() or item.target_epoch<>current_epoch
       or new.payload->>'format' is distinct from 'tokenworks-mobile-control-archive' or new.payload->>'version' is distinct from '1'
       or new.payload->>'domain_id' is distinct from item.domain_id::text or new.payload->>'export_id' is distinct from item.export_id::text
       or new.payload->>'operation_epoch' is distinct from item.source_epoch::text or new.payload->>'key_id' is distinct from item.key_id
       or new.payload->'batch'->>'id' is distinct from item.batch_id::text
       or new.payload->'batch'->>'database_epoch' is distinct from item.batch_epoch::text
       or (new.payload->'batch'->>'retain_until')::timestamptz is distinct from item.retain_until
       or jsonb_typeof(new.payload->'events') is distinct from 'array'
       or jsonb_array_length(new.payload->'events')<>item.event_count then
        raise exception 'archive import payload does not match identity' using errcode='23514';
    end if;
    if exists(select 1 from jsonb_array_elements(new.payload->'events') as event where jsonb_typeof(event->'acknowledgements') is distinct from 'array') then
        raise exception 'archive import acknowledgements are missing' using errcode='23514';
    end if;
    select coalesce(sum(jsonb_array_length(event->'acknowledgements')),0) into ack_count
      from jsonb_array_elements(new.payload->'events') as event;
    if ack_count<>item.acknowledgement_count then raise exception 'archive import acknowledgement count mismatch' using errcode='23514'; end if;
    return new;
end $$;

create or replace function cold_history_import_payload_guard() returns trigger language plpgsql as $$
declare item cold_history_imports%rowtype; current_epoch uuid; current_phase text; expected_format text; object_id text; source_epoch text; retained timestamptz; count_entries integer;
begin
    if tg_op='UPDATE' then raise exception 'cold history import payload is immutable' using errcode='23514'; end if;
    select * into item from cold_history_imports where id=case when tg_op='INSERT' then new.import_id else old.import_id end;
    select epoch,phase into current_epoch,current_phase from remote_recovery_state where slot=1 for share;
    if item.id is null or current_phase is distinct from 'ready' then raise exception 'cold history import is unavailable' using errcode='23514'; end if;
    if tg_op='DELETE' then
        if item.retain_until>clock_timestamp() then raise exception 'cold history import retention has not elapsed' using errcode='23514'; end if;
        insert into audit_log(actor_type,actor_id,action,result,metadata)
        values('system',current_user,'cold.history.import.delete','success',
            jsonb_build_object('import_id',item.id,'kind',item.kind,'domain_id',item.domain_id,'export_id',item.export_id,'current_epoch',current_epoch,'db_session_user',session_user));
        return old;
    end if;
    if item.import_xid<>pg_current_xact_id() or item.target_epoch<>current_epoch then
        raise exception 'cold history payload must be created with its import' using errcode='23514';
    end if;
    if item.kind='audit' then
        expected_format='tokenworks-mobile-audit-archive'; object_id=new.payload->'batch'->>'id';
        source_epoch=new.payload->'batch'->>'database_epoch'; retained=(new.payload->'batch'->>'retain_until')::timestamptz;
        if jsonb_typeof(new.payload->'entries') is distinct from 'array' then raise exception 'audit entries missing' using errcode='23514'; end if;
        count_entries=jsonb_array_length(new.payload->'entries');
    else
        expected_format='tokenworks-mobile-approval-evidence'; object_id=new.payload->'proof'->>'approval_id';
        source_epoch=new.payload->'proof'->>'source_epoch'; retained=(new.payload->'proof'->>'retain_until')::timestamptz;
        count_entries=1;
    end if;
    if new.payload->>'format' is distinct from expected_format or new.payload->>'version' is distinct from '1'
       or new.payload->>'domain_id' is distinct from item.domain_id::text or new.payload->>'export_id' is distinct from item.export_id::text
       or new.payload->>'operation_epoch' is distinct from item.operation_epoch::text or new.payload->>'key_id' is distinct from item.key_id
       or object_id is distinct from item.object_id::text or source_epoch is distinct from item.source_epoch::text
       or retained is distinct from item.retain_until or count_entries<>item.entry_count then
        raise exception 'cold history import payload does not match identity' using errcode='23514';
    end if;
    return new;
end $$;

commit;
