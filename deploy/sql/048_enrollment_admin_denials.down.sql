begin;
do $$ begin
    if exists(select 1 from enrollment_admin_denials) then
        raise exception 'administrator denial evidence prevents destructive rollback';
    end if;
end $$;
drop index enrollments_admin_directory;
drop trigger enrollment_admin_denial_terminal on enrollments;
drop function enrollment_admin_denial_terminal();
drop table enrollment_admin_denials;
drop function enrollment_admin_denial_owner();
drop function enrollment_admin_denial_immutable();
commit;
