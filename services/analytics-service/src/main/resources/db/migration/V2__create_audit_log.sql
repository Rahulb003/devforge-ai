-- An append-only record of every domain change, built from the events each service publishes:
-- who did what, in which project, when. Rows are inserted and never updated or deleted by the
-- application, so the trail cannot be edited after the fact through it.
CREATE TABLE audit_log (
    id UUID PRIMARY KEY,
    -- The event it records. Unique, so a redelivered event cannot appear twice.
    event_id UUID NOT NULL UNIQUE,
    event_type VARCHAR(100) NOT NULL,
    -- The service that published it.
    source VARCHAR(100),
    organization_id UUID,
    -- Null for organization-level changes.
    project_id UUID,
    -- Null when nobody authenticated caused it, e.g. a password reset by emailed link.
    actor_id UUID,
    occurred_at TIMESTAMP NOT NULL,
    -- The event payload: ids and names, which events are kept to by policy (EVENT_CATALOG.md).
    details TEXT NOT NULL
);

CREATE INDEX idx_audit_project ON audit_log(project_id, occurred_at DESC);
CREATE INDEX idx_audit_organization ON audit_log(organization_id, occurred_at DESC);
