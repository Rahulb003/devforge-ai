-- Daily activity per project, built by consuming domain events.
--
-- Pre-aggregated into day buckets rather than storing every event. The questions
-- this service answers are all "how much happened, and when" at day resolution,
-- and keeping a row per event would mean a table that grows without bound to
-- answer a query that never needs that detail. The events themselves remain in
-- Kafka for anything that does.
CREATE TABLE project_daily_metrics (
    id UUID PRIMARY KEY,
    -- Plain UUIDs, not foreign keys: projects live in project-service.
    project_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    -- The day the event occurred, in UTC. Derived from the event's own timestamp
    -- rather than from when it was consumed, so a replay or a slow consumer puts
    -- the activity on the day it actually happened.
    -- Not called "day": that is a reserved word in H2 and several other engines,
    -- and quoting it would only move the problem to whichever one comes next.
    metric_date DATE NOT NULL,
    tasks_created INTEGER NOT NULL DEFAULT 0,
    tasks_completed INTEGER NOT NULL DEFAULT 0,
    tasks_assigned INTEGER NOT NULL DEFAULT 0,
    commits INTEGER NOT NULL DEFAULT 0,
    repositories_created INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL
);

-- One row per project per day. The unique constraint is what makes the consumer's
-- read-modify-write safe to retry: a concurrent insert loses to it rather than
-- creating a duplicate bucket that would split a day's counts in two.
CREATE UNIQUE INDEX idx_metrics_project_day ON project_daily_metrics(project_id, metric_date);
CREATE INDEX idx_metrics_org_day ON project_daily_metrics(organization_id, metric_date);

-- Consumer deduplication. Essential here rather than merely good practice: these
-- are counters, so processing one event twice silently inflates a number that
-- nobody can later tell is wrong.
CREATE TABLE processed_events (
    event_id UUID NOT NULL,
    consumer_group VARCHAR(150) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    processed_at TIMESTAMP NOT NULL,
    PRIMARY KEY (event_id, consumer_group)
);

CREATE INDEX idx_processed_events_processed_at ON processed_events(processed_at);

-- The outbox tables exist because the shared entities are on this service's
-- classpath and Hibernate validates every mapped entity at startup. This service
-- consumes and publishes nothing, so the publisher stays off and these stay empty.
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
