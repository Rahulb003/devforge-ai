-- Review on pull requests: comments, approvals, and a per-repository merge rule.

-- How many approvals of the current changes a pull request needs before it can
-- merge. 0 keeps merging open to any writer, as before.
ALTER TABLE repositories ADD COLUMN required_approvals INT NOT NULL DEFAULT 0;

CREATE TABLE pull_request_comments (
    id UUID PRIMARY KEY,
    pull_request_id UUID NOT NULL REFERENCES pull_requests(id) ON DELETE CASCADE,
    author_id UUID NOT NULL,
    -- Captured from the author's token when written: user names live in
    -- auth-service, which this service does not query. As in chat-service.
    author_name VARCHAR(100) NOT NULL,
    body VARCHAR(10000) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_pr_comments_pull_request ON pull_request_comments(pull_request_id, created_at);

-- One approval per reviewer per pull request, pinned to the source commit they
-- approved. Only approvals of the commit being merged count, so pushing new
-- changes after an approval does not ride on it.
CREATE TABLE pull_request_approvals (
    id UUID PRIMARY KEY,
    pull_request_id UUID NOT NULL REFERENCES pull_requests(id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    user_name VARCHAR(100) NOT NULL,
    commit_id VARCHAR(40) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX idx_pr_approvals_reviewer ON pull_request_approvals(pull_request_id, user_id);
