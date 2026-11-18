-- Apply to each existing or fresh native-transport database before setting REMOTE_SESSION_SHARING=true.
BEGIN;
CREATE TABLE public.session_shares (
    id_hash bytea PRIMARY KEY CHECK (octet_length(id_hash) = 32),
    publisher_id uuid NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    epoch uuid NOT NULL,
    ciphertext bytea NOT NULL CHECK (octet_length(ciphertext) BETWEEN 29 AND 2097152),
    expires_at timestamptz NOT NULL
);
CREATE INDEX session_shares_quota ON public.session_shares (publisher_id, epoch, expires_at);
CREATE INDEX session_shares_expiration ON public.session_shares (expires_at);
COMMIT;
