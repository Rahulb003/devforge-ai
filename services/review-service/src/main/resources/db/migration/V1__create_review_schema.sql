-- A review is one analysis run over one repository at one ref.
CREATE TABLE reviews (
    id UUID PRIMARY KEY,
    -- Plain UUIDs, not foreign keys: projects live in project-service and
    -- repositories in git-service. Services must not reach into each other's
    -- schemas, so access is checked against the owning service at request time.
    project_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    repository_id UUID NOT NULL,
    -- What was analysed. base_ref is null for a whole-tree review, set when the
    -- review covers only what changed between two refs.
    ref VARCHAR(255) NOT NULL,
    base_ref VARCHAR(255),
    status VARCHAR(30) NOT NULL,
    -- PASS or FAIL, decided from the findings by the quality gate. Null while
    -- the review is still running or if it failed before producing findings.
    gate VARCHAR(20),
    -- Denormalised counts, so a list of reviews does not need a join per row.
    files_analysed INTEGER NOT NULL DEFAULT 0,
    blocker_count INTEGER NOT NULL DEFAULT 0,
    high_count INTEGER NOT NULL DEFAULT 0,
    medium_count INTEGER NOT NULL DEFAULT 0,
    low_count INTEGER NOT NULL DEFAULT 0,
    -- Set when analysis could not complete, e.g. git-service was unreachable.
    failure_reason VARCHAR(1000),
    requested_by UUID NOT NULL,
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP
);

CREATE INDEX idx_reviews_repository ON reviews(repository_id, created_at DESC);
CREATE INDEX idx_reviews_project ON reviews(project_id, created_at DESC);

CREATE TABLE review_findings (
    id UUID PRIMARY KEY,
    review_id UUID NOT NULL REFERENCES reviews(id) ON DELETE CASCADE,
    -- Stable identifier for the rule, e.g. SECRET_AWS_ACCESS_KEY. Used to
    -- suppress a rule or compare runs, so it must not change once published.
    rule_id VARCHAR(100) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    category VARCHAR(40) NOT NULL,
    file_path VARCHAR(1024) NOT NULL,
    -- 1-based. Null when the finding is about the file as a whole.
    line_number INTEGER,
    message VARCHAR(1000) NOT NULL,
    -- The offending line, REDACTED for secrets. A finding that quotes a key
    -- copies that key into this table, the API response and the logs, which
    -- turns a detection into a second place the secret leaks from.
    snippet VARCHAR(500),
    dismissed_at TIMESTAMP,
    dismissed_by UUID,
    dismiss_reason VARCHAR(500),
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_findings_review ON review_findings(review_id, severity);

-- Transactional outbox and consumer deduplication, as in the other services.
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

CREATE TABLE processed_events (
    event_id UUID NOT NULL,
    consumer_group VARCHAR(150) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    processed_at TIMESTAMP NOT NULL,
    PRIMARY KEY (event_id, consumer_group)
);

CREATE INDEX idx_processed_events_processed_at ON processed_events(processed_at);
