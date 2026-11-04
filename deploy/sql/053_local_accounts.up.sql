begin;
-- Local credentials are separate from OIDC users; usernames never link identities.
create table local_accounts (
    user_id uuid primary key references users(id),
    username text not null unique check (username ~ '^[a-z0-9][a-z0-9_.-]{2,31}$'),
    password_hash text not null,
    password_version bigint not null default 1 check (password_version > 0),
    created_at timestamptz not null default clock_timestamp()
);
create table account_sessions (
    token_hash bytea primary key check (octet_length(token_hash)=32),
    csrf_hash bytea not null check (octet_length(csrf_hash)=32),
    user_id uuid not null references local_accounts(user_id),
    password_version bigint not null,
    database_epoch uuid not null,
    created_at timestamptz not null default clock_timestamp(),
    expires_at timestamptz not null,
    check (expires_at > created_at)
);
create index account_sessions_user on account_sessions(user_id,created_at);
create index account_sessions_expiry on account_sessions(expires_at);
create table account_login_limits (
    bucket_hash bytea primary key check (octet_length(bucket_hash)=32),
    started_at timestamptz not null default clock_timestamp(),
    attempts integer not null default 1 check (attempts > 0)
);
commit;
