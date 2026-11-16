CREATE TABLE IF NOT EXISTS shares (
 id uuid PRIMARY KEY,
 owner text NOT NULL,
 recipient text NOT NULL,
 name text NOT NULL,
 format text NOT NULL,
 duration integer,
 invite text UNIQUE NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 published_at timestamptz,
 expires_at timestamptz,
 wrapped bytea,
 ciphertext bytea,
 invitation_delivered boolean NOT NULL DEFAULT false
);
CREATE TABLE IF NOT EXISTS challenges (
 share_id uuid PRIMARY KEY REFERENCES shares(id) ON DELETE CASCADE,
 receiver text NOT NULL,
 nonce text NOT NULL,
 verifier bytea NOT NULL,
 issued_at timestamptz NOT NULL DEFAULT now(),
 failures integer NOT NULL DEFAULT 0,
 used boolean NOT NULL DEFAULT false
);
CREATE TABLE IF NOT EXISTS sessions (
 token text PRIMARY KEY,
 share_id uuid NOT NULL REFERENCES shares(id) ON DELETE CASCADE,
 expires_at timestamptz NOT NULL
);
