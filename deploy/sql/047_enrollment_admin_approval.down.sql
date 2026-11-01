begin;
do $$ begin
    if exists(select 1 from orgs where enrollment_admin_approval_required)
       or exists(select 1 from enrollments where admin_approval_required or admin_approved_by is not null) then
        raise exception 'enrollment administrator policy or evidence prevents destructive rollback';
    end if;
end $$;
drop trigger enrollment_admin_policy_tighten on orgs;
drop function enrollment_admin_policy_tighten();
drop trigger enrollment_admin_gate on enrollments;
drop function enrollment_admin_gate();
alter table enrollments drop constraint enrollment_admin_device_owner;
alter table enrollments drop constraint enrollment_admin_approval_shape;
alter table enrollments drop constraint enrollment_admin_activation_gate;
alter table enrollments drop column admin_approval_required;
alter table enrollments drop column admin_approved_by;
alter table enrollments drop column admin_approved_device_id;
alter table enrollments drop column admin_approved_device_key_version;
alter table enrollments drop column admin_approved_at;
alter table enrollments drop column admin_review_sha256;
alter table orgs drop column enrollment_admin_approval_required;
commit;
