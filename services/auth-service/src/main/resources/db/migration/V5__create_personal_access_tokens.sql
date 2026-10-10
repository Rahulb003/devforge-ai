-- Personal access tokens: the credential a git client sends, since it cannot hold a browser session.
--
-- Only a SHA-256 of the token is stored. The token is 256 random bits, so a hash with no salt or
-- stretching is enough: there is no dictionary to attack, and a leaked table yields nothing usable.
-- The prefix is the first characters of the token itself, kept so a user can tell their tokens apart.
CREATE TABLE personal_access_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    name VARCHAR(100) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    token_prefix VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    -- Always set: a credential that never expires is one nobody remembers to revoke.
    expires_at TIMESTAMP NOT NULL,
    last_used_at TIMESTAMP,
    revoked_at TIMESTAMP,
    CONSTRAINT fk_pat_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_pat_user ON personal_access_tokens(user_id, created_at DESC);
