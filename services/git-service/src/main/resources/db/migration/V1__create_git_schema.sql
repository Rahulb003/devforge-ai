-- Repository metadata. The git objects themselves live on disk, managed by JGit;
-- this table is the index over them and the authorization record.
CREATE TABLE repositories (
    id UUID PRIMARY KEY,
    -- Plain UUIDs, not foreign keys: projects and organizations live in
    -- project-service's database and services must not reach into each other's
    -- schemas. Access is checked against project-service at request time.
    project_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    -- Human-facing name, e.g. "payments-api".
    name VARCHAR(100) NOT NULL,
    description VARCHAR(1000),
    -- Branch a browse request lands on when no ref is given.
    default_branch VARCHAR(255) NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    version BIGINT NOT NULL
);

-- Names are unique within a project, not globally: two teams may each have a
-- repository called "api" and neither should block the other.
CREATE UNIQUE INDEX idx_repositories_project_name ON repositories(project_id, name);
CREATE INDEX idx_repositories_project ON repositories(project_id, created_at DESC);

-- There is deliberately no storage_path column. The on-disk location is derived
-- from the organization and repository ids at read time, so a row cannot be
-- edited to point the service at an arbitrary directory, and renaming a
-- repository never has to move files.

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
