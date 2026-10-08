-- One channel per project: membership is project membership, which project-service
-- already owns, so there is no second access list to drift out of step.
CREATE TABLE chat_messages (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    author_id UUID NOT NULL,
    -- Captured at write time. The author's name may change later; the message
    -- records who they were when they said it, and needs no call to auth-service.
    author_name VARCHAR(100) NOT NULL,
    body VARCHAR(4000) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    edited_at TIMESTAMP,
    -- Soft delete: the row stays so the conversation keeps its shape, but the
    -- body is cleared, because a deleted message must not remain readable.
    deleted_at TIMESTAMP
);

CREATE INDEX idx_chat_project_created ON chat_messages(project_id, created_at DESC);

CREATE TABLE processed_events (
    event_id UUID NOT NULL,
    consumer_group VARCHAR(150) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    processed_at TIMESTAMP NOT NULL,
    PRIMARY KEY (event_id, consumer_group)
);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    event_type VARCHAR(100) NOT NULL,
    topic VARCHAR(150) NOT NULL,
    partition_key VARCHAR(200),
    payload TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    published_at TIMESTAMP,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(1000)
);
