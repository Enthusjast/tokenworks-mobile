begin;
create table enrollment_admin_denials (
    enrollment_id uuid primary key references enrollments(id),
    denied_by uuid not null references users(id),
    device_id uuid not null,
    device_key_version bigint not null check(device_key_version>0),
    review_sha256 bytea not null check(octet_length(review_sha256)=32),
    denied_at timestamptz not null default clock_timestamp(),
    foreign key(denied_by,device_id) references devices(user_id,id)
);

create function enrollment_admin_denial_owner() returns trigger language plpgsql as $$
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
create trigger enrollment_admin_denial_owner before insert on enrollment_admin_denials
    for each row execute function enrollment_admin_denial_owner();

create function enrollment_admin_denial_immutable() returns trigger language plpgsql as $$
begin
    raise exception 'enrollment administrator denial evidence is immutable' using errcode='23514';
end $$;
create trigger enrollment_admin_denial_immutable before update or delete on enrollment_admin_denials
    for each row execute function enrollment_admin_denial_immutable();
create trigger enrollment_admin_denial_no_truncate before truncate on enrollment_admin_denials
    for each statement execute function enrollment_admin_denial_immutable();

create function enrollment_admin_denial_terminal() returns trigger language plpgsql as $$
begin
    if new is distinct from old and exists(select 1 from enrollment_admin_denials where enrollment_id=old.id) then
        raise exception 'recorded enrollment denial is terminal' using errcode='23514';
    end if;
    return new;
end $$;
create trigger enrollment_admin_denial_terminal before update on enrollments
    for each row when (old.state='denied') execute function enrollment_admin_denial_terminal();

-- The organization is selected through hosts. Filter historical expired rows
-- before sorting the small live pending set; cursors convey no authority.
create index enrollments_admin_directory on enrollments(host_id,expires_at,created_at,id)
    where admin_approval_required and state in('pending','user_confirmed');
commit;
