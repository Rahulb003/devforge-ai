-- Every sandboxed run, written before it starts and completed after: an execution feature with no
-- audit trail cannot be investigated after the fact (docs/SANDBOX.md section 3). A row left at
-- RUNNING is a run whose instance died mid-way, which is itself worth knowing.
CREATE TABLE executions (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    project_id UUID NOT NULL,
    user_id UUID NOT NULL,
    language VARCHAR(20) NOT NULL,
    code TEXT NOT NULL,
    -- The limits actually applied, after clamping, not the ones asked for.
    timeout_seconds INTEGER NOT NULL,
    memory_mb INTEGER NOT NULL,
    output_bytes INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    exit_code INTEGER,
    stdout TEXT,
    stderr TEXT,
    truncated BOOLEAN NOT NULL DEFAULT FALSE,
    duration_ms BIGINT,
    created_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP
);

CREATE INDEX idx_executions_project_created ON executions(project_id, created_at DESC);
-- The quota counts a user's recent runs.
CREATE INDEX idx_executions_user_created ON executions(user_id, created_at);
