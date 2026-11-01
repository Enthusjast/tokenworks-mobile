begin;
do $$ begin
    if exists(select 1 from enrollment_terminal_checks) then
        raise exception 'observed terminal evidence prevents destructive rollback';
    end if;
end $$;
drop trigger enrollment_observed_terminal on enrollments;
drop function enrollment_observed_terminal();
drop table enrollment_terminal_checks;
drop function enrollment_terminal_check_owner();
drop function enrollment_terminal_check_immutable();
commit;
