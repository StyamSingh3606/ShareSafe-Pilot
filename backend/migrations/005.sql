ALTER TABLE sender_registration_challenges
  ADD COLUMN IF NOT EXISTS purpose text NOT NULL DEFAULT 'registration';
CREATE INDEX IF NOT EXISTS sender_registration_challenges_email_purpose
  ON sender_registration_challenges (email, purpose);
