begin;
create table enrollment_terminal_checks (
    enrollment_id uuid primary key references enrollments(id),
    state text not null check(state in('denied','expired','cancelled')),
    owner_user_id uuid not null references users(id),
    installation_key_thumbprint text not null check(length(installation_key_thumbprint)=43),
    observed_at timestamptz not null default clock_timestamp()
);
create function enrollment_terminal_check_owner() returns trigger language plpgsql as $$
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
create trigger enrollment_terminal_check_owner before insert on enrollment_terminal_checks
    for each row execute function enrollment_terminal_check_owner();
create function enrollment_terminal_check_immutable() returns trigger language plpgsql as $$
begin
    raise exception 'observed enrollment terminal evidence is immutable' using errcode='23514';
end $$;
create trigger enrollment_terminal_check_immutable before update or delete on enrollment_terminal_checks
    for each row execute function enrollment_terminal_check_immutable();
create trigger enrollment_terminal_check_no_truncate before truncate on enrollment_terminal_checks
    for each statement execute function enrollment_terminal_check_immutable();
create function enrollment_observed_terminal() returns trigger language plpgsql as $$
begin
    if new is distinct from old and exists(select 1 from enrollment_terminal_checks where enrollment_id=old.id) then
        raise exception 'observed enrollment terminal state cannot change' using errcode='23514';
    end if;
    return new;
end $$;
create trigger enrollment_observed_terminal before update on enrollments
    for each row when(old.state in('denied','expired','cancelled')) execute function enrollment_observed_terminal();
commit;
