-- Transactional outbox.
--
-- Events are written here in the same transaction as the state change they
-- describe, so the two commit or roll back together. Publishing to Kafka
-- inline would either lose events when the broker is briefly unavailable after
-- a successful commit, or announce changes that were rolled back.
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    -- Mirrors the envelope's eventId; this is the consumer deduplication key.
    event_id UUID NOT NULL UNIQUE,
    event_type VARCHAR(100) NOT NULL,
    topic VARCHAR(150) NOT NULL,
    partition_key VARCHAR(200),
    payload TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    -- NULL until the broker has durably acknowledged the send.
    published_at TIMESTAMP,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(1000)
);

-- The publisher polls for unpublished rows in insertion order; this index is
-- what keeps that poll from degrading into a full scan as the table grows.
CREATE INDEX idx_outbox_unpublished ON outbox_events(published_at, created_at);

-- Consumer-side deduplication.
--
-- Delivery is at-least-once: the outbox resends after a crash between a
-- successful send and the row being marked published, and Kafka redelivers
-- whenever a rebalance interrupts an offset commit. Handlers with side effects
-- check here first.
CREATE TABLE processed_events (
    event_id UUID NOT NULL,
    -- Composite key: two consumer groups must each process the same event once.
    consumer_group VARCHAR(150) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    processed_at TIMESTAMP NOT NULL,
    PRIMARY KEY (event_id, consumer_group)
);

CREATE INDEX idx_processed_events_processed_at ON processed_events(processed_at);
