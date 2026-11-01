begin;
lock table app_release_artifacts in access exclusive mode;
do $$ begin
    if exists(select 1 from app_release_artifacts) then
        raise exception 'registered APK identities prevent destructive rollback';
    end if;
end $$;
drop table app_release_artifacts;
drop function app_release_artifact_guard();
commit;
