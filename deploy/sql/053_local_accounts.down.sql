begin;
drop table account_login_limits;
drop table account_sessions;
drop table local_accounts;
-- Preserve user identities and existing audit history.
commit;
