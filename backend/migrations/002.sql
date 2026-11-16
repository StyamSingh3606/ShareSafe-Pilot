CREATE TABLE IF NOT EXISTS sender_sessions (
 token text PRIMARY KEY,
 owner text NOT NULL,
 expires_at timestamptz NOT NULL
);
CREATE TABLE IF NOT EXISTS request_windows (
 name text PRIMARY KEY,
 opened_at timestamptz NOT NULL DEFAULT now(),
 attempts integer NOT NULL
);
