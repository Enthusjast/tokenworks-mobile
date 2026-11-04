begin;
create function revoke_inactive_account_sessions() returns trigger language plpgsql as $$
begin
    if new.status <> 'active' then
        delete from account_sessions where user_id=new.id;
    end if;
    return new;
end $$;
create trigger revoke_inactive_account_sessions after update of status on users
    for each row when (new.status is distinct from old.status)
    execute function revoke_inactive_account_sessions();
commit;
