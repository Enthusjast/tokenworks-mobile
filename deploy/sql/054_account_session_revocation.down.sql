begin;
drop trigger revoke_inactive_account_sessions on users;
drop function revoke_inactive_account_sessions();
commit;
