-- Pull requests: a proposal to merge one branch of a repository into another.
--
-- The branches themselves live in git. A row records the proposal and its
-- outcome; what it would change is always computed from the branches as they
-- are now, never stored, so it cannot go stale.
CREATE TABLE pull_requests (
    id UUID PRIMARY KEY,
    -- Same service, same database: a real foreign key, so deleting a
    -- repository removes its pull requests rather than orphaning them.
    repository_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    -- Per-repository sequence, the number people say out loud ("#12").
    number INT NOT NULL,
    title VARCHAR(200) NOT NULL,
    description VARCHAR(10000),
    source_branch VARCHAR(255) NOT NULL,
    target_branch VARCHAR(255) NOT NULL,
    -- OPEN, MERGED or CLOSED.
    status VARCHAR(20) NOT NULL,
    author_id UUID NOT NULL,
    merged_by UUID,
    merge_commit_id VARCHAR(40),
    closed_by UUID,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    closed_at TIMESTAMP,
    version BIGINT NOT NULL
);

CREATE UNIQUE INDEX idx_pull_requests_number ON pull_requests(repository_id, number);
CREATE INDEX idx_pull_requests_status ON pull_requests(repository_id, status, number DESC);
