begin;
alter table orgs add column enrollment_admin_approval_required boolean not null default false;
alter table enrollments add column admin_approval_required boolean not null default false;
alter table enrollments add column admin_approved_by uuid references users(id);
alter table enrollments add column admin_approved_device_id uuid;
alter table enrollments add column admin_approved_device_key_version bigint;
alter table enrollments add column admin_approved_at timestamptz;
alter table enrollments add column admin_review_sha256 bytea;
alter table enrollments add constraint enrollment_admin_device_owner
    foreign key(admin_approved_by,admin_approved_device_id) references devices(user_id,id);
alter table enrollments add constraint enrollment_admin_approval_shape check (
    (admin_approved_by is null and admin_approved_device_id is null
     and admin_approved_device_key_version is null and admin_approved_at is null and admin_review_sha256 is null)
    or (admin_approved_by is not null and admin_approved_device_id is not null
        and admin_approved_device_key_version is not null and admin_approved_device_key_version > 0
        and admin_approved_at is not null and admin_review_sha256 is not null
        and octet_length(admin_review_sha256)=32 and admin_approval_required
        and state='approved' and user_confirmed_by is not null and user_confirmed_at is not null
        and user_confirmed_device_id is not null and host_confirmed_at is not null)
);
alter table enrollments add constraint enrollment_admin_activation_gate check (
    state<>'approved' or not admin_approval_required or admin_approved_by is not null
);

-- Enforce the gate even when an old application node omits the new columns.
-- The organization lock orders policy tightening against activation. A pending
-- requirement can only tighten; relaxing policy never releases old requests.
create function enrollment_admin_gate() returns trigger language plpgsql as $$
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
create trigger enrollment_admin_gate before insert or update on enrollments
    for each row execute function enrollment_admin_gate();

create function enrollment_admin_policy_tighten() returns trigger language plpgsql as $$
begin
    if new.enrollment_admin_approval_required and not old.enrollment_admin_approval_required then
        update enrollments e set admin_approval_required=true from hosts h
            where h.id=e.host_id and h.org_id=new.id and e.state in('pending','user_confirmed')
            and not e.admin_approval_required;
    end if;
    return new;
end $$;
create trigger enrollment_admin_policy_tighten after update of enrollment_admin_approval_required on orgs
    for each row execute function enrollment_admin_policy_tighten();
commit;
