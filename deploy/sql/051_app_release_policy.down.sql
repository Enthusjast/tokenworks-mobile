begin;
lock table app_release_policy_changes,app_release_policies in access exclusive mode;
do $$ begin
    if exists(select 1 from app_release_policies) or exists(select 1 from app_release_policy_changes) then
        raise exception 'release policy history prevents destructive rollback';
    end if;
end $$;
drop table app_release_policy_changes;
drop table app_release_policies;
drop function app_release_policy_change_guard();
drop function app_release_policy_guard();
commit;
