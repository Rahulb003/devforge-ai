-- A pipeline run: the steps in a repository's .devforge/pipeline.yml, run in the sandbox against
-- the repository at one ref. Repository and project ids are plain UUIDs: they belong to other
-- services, so there is no foreign key across the boundary.
CREATE TABLE pipeline_runs (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    project_id UUID NOT NULL,
    repository_id UUID NOT NULL,
    ref VARCHAR(255) NOT NULL,
    triggered_by UUID NOT NULL,
    status VARCHAR(20) NOT NULL,
    -- Why the run ended the way it did, when the steps alone do not say.
    message VARCHAR(2000),
    -- Repository files left out of the workspace (binary or too large), so a step that fails for
    -- want of one is explained rather than mysterious.
    omitted_files TEXT,
    created_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP,
    finished_at TIMESTAMP
);

CREATE INDEX idx_pipeline_runs_repository ON pipeline_runs(repository_id, created_at DESC);
CREATE INDEX idx_pipeline_runs_user_created ON pipeline_runs(triggered_by, created_at);

CREATE TABLE pipeline_steps (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES pipeline_runs(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    name VARCHAR(60) NOT NULL,
    runtime VARCHAR(20) NOT NULL,
    script TEXT NOT NULL,
    timeout_seconds INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    exit_code INTEGER,
    stdout TEXT,
    stderr TEXT,
    truncated BOOLEAN NOT NULL DEFAULT FALSE,
    duration_ms BIGINT,
    UNIQUE (run_id, position)
);
