CREATE TABLE IF NOT EXISTS sender_registration_challenges (
 id text PRIMARY KEY,
 email text NOT NULL,
 verifier bytea NOT NULL,
 expires_at timestamptz NOT NULL,
 attempts integer NOT NULL DEFAULT 0
);
