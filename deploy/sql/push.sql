-- Apply once to an existing native-transport/v1 database before enabling push.
-- Fresh deployments already include this schema in baseline.sql.
BEGIN;
ALTER TABLE public.devices DROP COLUMN push_provider, DROP COLUMN push_token_ciphertext;
CREATE TABLE public.push_registrations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id uuid NOT NULL,
    org_id uuid NOT NULL REFERENCES public.orgs(id),
    user_id uuid NOT NULL REFERENCES public.users(id),
    device_id uuid NOT NULL REFERENCES public.devices(id),
    device_key_version bigint NOT NULL,
    proof_key_thumbprint text NOT NULL,
    deployment_sha256 text NOT NULL CHECK (deployment_sha256 ~ '^[0-9a-f]{64}$'),
    owner_fingerprint text NOT NULL CHECK (owner_fingerprint ~ '^[0-9a-f]{64}$'),
    token_sha256 bytea NOT NULL UNIQUE CHECK (octet_length(token_sha256) = 32),
    token_ciphertext bytea NOT NULL CHECK (octet_length(token_ciphertext) >= 29),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (device_id)
);
CREATE TABLE public.push_deliveries (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    binding_id uuid NOT NULL REFERENCES public.push_registrations(id) ON DELETE CASCADE,
    notification_id uuid NOT NULL,
    installation_id uuid NOT NULL REFERENCES public.installations(id),
    installation_key_version bigint NOT NULL,
    runtime_id uuid NOT NULL REFERENCES public.runtimes(id),
    session_id text NOT NULL CHECK (length(session_id) BETWEEN 1 AND 256),
    kind text NOT NULL CHECK (kind IN ('interaction', 'completed')),
    expires_at timestamptz NOT NULL,
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    delivered_at timestamptz,
    UNIQUE (binding_id, notification_id)
);
CREATE INDEX push_deliveries_pending ON public.push_deliveries(next_attempt_at, id) WHERE delivered_at IS NULL;
COMMIT;
