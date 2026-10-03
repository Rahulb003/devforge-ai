-- Notifications are per-recipient. There is no row that belongs to "everyone":
-- a fan-out event creates one row per person, so read state, deletion and
-- authorization are all per-user with no shared mutable row.
CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    -- Who sees this. Every query is filtered by this column, taken from the
    -- caller's verified token and never from a path or query parameter.
    recipient_id UUID NOT NULL,
    -- Plain UUID, not a foreign key: users live in auth-service's database and
    -- services must not reach into each other's schemas.
    organization_id UUID,
    category VARCHAR(40) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    title VARCHAR(200) NOT NULL,
    body VARCHAR(1000),
    -- Where the notification points, e.g. a task. Relative, so the frontend owns
    -- routing and a stored absolute URL cannot rot or point off-origin.
    link VARCHAR(500),
    -- The event that produced this row, kept so a notification can be traced
    -- back to its cause across services.
    source_event_id UUID,
    correlation_id VARCHAR(64),
    read_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL
);

-- The list query: one recipient's notifications, newest first.
CREATE INDEX idx_notifications_recipient ON notifications(recipient_id, created_at DESC);

-- The unread badge, which is polled far more often than the list itself.
CREATE INDEX idx_notifications_unread ON notifications(recipient_id, read_at);

-- Consumer deduplication, as in auth-service and task-service. This service is a
-- consumer, so this table is the one that carries the weight: without it a
-- redelivered event would create a second copy of the same notification.
CREATE TABLE processed_events (
    event_id UUID NOT NULL,
    consumer_group VARCHAR(150) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    processed_at TIMESTAMP NOT NULL,
    PRIMARY KEY (event_id, consumer_group)
);

CREATE INDEX idx_processed_events_processed_at ON processed_events(processed_at);

-- The outbox tables exist because the shared outbox entities are on this
-- service's classpath and Hibernate validates every mapped entity against the
-- schema at startup. This service publishes nothing yet, so the publisher is
-- switched off (devforge.outbox.enabled=false) and these stay empty. They are
-- here to keep the mapping valid, not to imply a feature.
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

CREATE INDEX idx_outbox_unpublished ON outbox_events(published_at, created_at);
