-- One generation run over one repository at one ref.
CREATE TABLE doc_sets (
    id UUID PRIMARY KEY,
    -- Plain UUIDs, not foreign keys: projects live in project-service and
    -- repositories in git-service. Services must not reach into each other's
    -- schemas, so access is checked against the owning service at request time.
    project_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    repository_id UUID NOT NULL,
    ref VARCHAR(255) NOT NULL,
    status VARCHAR(30) NOT NULL,
    files_scanned INTEGER NOT NULL DEFAULT 0,
    -- Set when generation could not complete, e.g. git-service was unreachable.
    -- A doc set that could not read the code must never look like an empty one.
    failure_reason VARCHAR(1000),
    generated_by UUID NOT NULL,
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP
);

CREATE INDEX idx_doc_sets_repository ON doc_sets(repository_id, created_at DESC);

CREATE TABLE documents (
    id UUID PRIMARY KEY,
    doc_set_id UUID NOT NULL REFERENCES doc_sets(id) ON DELETE CASCADE,
    -- OVERVIEW | API_SURFACE | DOC_COVERAGE. Stable, because a client links to a
    -- document by kind rather than by id.
    kind VARCHAR(40) NOT NULL,
    title VARCHAR(200) NOT NULL,
    -- Rendered Markdown. Stored rather than regenerated on read: a document
    -- describes the repository at one ref, so regenerating it later would
    -- silently answer a different question.
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);

-- One document per kind per set, so a client can address it by kind.
CREATE UNIQUE INDEX idx_documents_set_kind ON documents(doc_set_id, kind);

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
