-- Webhooks: an HTTP POST to a URL of the repository admin's choosing whenever a branch changes, so
-- CI systems and chat bots elsewhere can react. Signed with the webhook's own secret.
CREATE TABLE repository_webhooks (
    id UUID PRIMARY KEY,
    repository_id UUID NOT NULL,
    url VARCHAR(2000) NOT NULL,
    -- Kept to sign each delivery; shown to the admin once, when the webhook is created.
    secret VARCHAR(100) NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMP NOT NULL,
    -- The last delivery's outcome, so an admin can see a receiver that stopped answering.
    last_status INTEGER,
    last_error VARCHAR(500),
    last_delivered_at TIMESTAMP,
    CONSTRAINT fk_webhooks_repository FOREIGN KEY (repository_id) REFERENCES repositories(id) ON DELETE CASCADE
);

CREATE INDEX idx_webhooks_repository ON repository_webhooks(repository_id);
