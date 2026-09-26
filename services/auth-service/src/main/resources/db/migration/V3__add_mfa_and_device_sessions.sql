-- Multi-factor authentication -------------------------------------------------

-- The TOTP shared secret is stored per user. It is a credential: anyone holding
-- it can generate valid codes indefinitely, so it must never be logged or
-- returned by an API after enrolment completes.
ALTER TABLE users ADD COLUMN mfa_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN mfa_secret VARCHAR(255);
ALTER TABLE users ADD COLUMN mfa_enrolled_at TIMESTAMP;

-- Single-use recovery codes for when the authenticator device is lost.
-- Only a hash is stored, exactly as for a password: a leak of this table must
-- not hand an attacker working second factors.
CREATE TABLE mfa_backup_codes (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    code_hash VARCHAR(200) NOT NULL,
    used_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_mfa_backup_codes_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX idx_mfa_backup_codes_user_id ON mfa_backup_codes(user_id);

-- Per-device sessions ---------------------------------------------------------

-- Refresh tokens previously held one row per user, so signing in on a second
-- device silently signed the first one out, and there was no way to see or
-- revoke an individual session. These columns let each device hold its own
-- session and be listed and revoked independently.
ALTER TABLE refresh_tokens ADD COLUMN device_label VARCHAR(150);
ALTER TABLE refresh_tokens ADD COLUMN user_agent VARCHAR(500);
ALTER TABLE refresh_tokens ADD COLUMN ip_address VARCHAR(45);
ALTER TABLE refresh_tokens ADD COLUMN last_used_at TIMESTAMP;

-- Sessions are listed per user and expired sessions are swept by date.
CREATE INDEX idx_refresh_tokens_user_expires ON refresh_tokens(user_id, expires_at);
