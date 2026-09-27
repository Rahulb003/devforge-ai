-- Sprints are a time-boxed grouping of work within one project.
CREATE TABLE sprints (
    id UUID PRIMARY KEY,
    -- Plain UUID, not a foreign key: projects live in project-service's database
    -- and services must not reach into each other's schemas. Access to a project
    -- is checked against project-service at request time.
    project_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    name VARCHAR(150) NOT NULL,
    goal VARCHAR(1000),
    status VARCHAR(50) NOT NULL,
    start_date DATE,
    end_date DATE,
    created_by UUID NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP,
    version BIGINT NOT NULL
);

CREATE TABLE tasks (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    -- Human-facing sequence within the project, e.g. CORE-1. Unique per project
    -- rather than globally, so each project counts from one.
    task_number INTEGER NOT NULL,
    title VARCHAR(300) NOT NULL,
    description VARCHAR(10000),
    status VARCHAR(50) NOT NULL,
    priority VARCHAR(50) NOT NULL,
    type VARCHAR(50) NOT NULL,
    assignee_id UUID,
    reporter_id UUID NOT NULL,
    story_points INTEGER,
    due_date DATE,
    -- Subtasks point at their parent. Self-referencing, so it stays in this table.
    parent_task_id UUID,
    sprint_id UUID,
    -- Ordering within a Kanban column. Positions are contiguous from zero and
    -- the affected columns are renumbered on every move, which keeps them from
    -- drifting at the cost of writing a handful of rows per drag.
    board_position INTEGER NOT NULL DEFAULT 0,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP,
    version BIGINT NOT NULL,
    CONSTRAINT fk_tasks_parent FOREIGN KEY (parent_task_id) REFERENCES tasks(id),
    CONSTRAINT fk_tasks_sprint FOREIGN KEY (sprint_id) REFERENCES sprints(id),
    CONSTRAINT uq_tasks_project_number UNIQUE (project_id, task_number)
);

CREATE TABLE task_comments (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL,
    author_id UUID NOT NULL,
    body VARCHAR(10000) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP,
    version BIGINT NOT NULL,
    CONSTRAINT fk_task_comments_task FOREIGN KEY (task_id) REFERENCES tasks(id)
);

CREATE TABLE task_labels (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL,
    label VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP,
    version BIGINT NOT NULL,
    CONSTRAINT fk_task_labels_task FOREIGN KEY (task_id) REFERENCES tasks(id),
    -- The same label twice on one task is meaningless and would duplicate in the UI.
    CONSTRAINT uq_task_labels_task_label UNIQUE (task_id, label)
);

-- Allocates the per-project task number. A dedicated row per project, updated
-- under a row lock, so two concurrent creates cannot claim the same number —
-- which the unique constraint above would otherwise reject outright.
CREATE TABLE task_number_sequences (
    project_id UUID PRIMARY KEY,
    next_number INTEGER NOT NULL
);

-- Every task query is scoped by project, and the board reads by status.
CREATE INDEX idx_tasks_project ON tasks(project_id);
CREATE INDEX idx_tasks_project_status ON tasks(project_id, status, board_position);
CREATE INDEX idx_tasks_assignee ON tasks(assignee_id);
CREATE INDEX idx_tasks_sprint ON tasks(sprint_id);
CREATE INDEX idx_tasks_parent ON tasks(parent_task_id);
CREATE INDEX idx_task_comments_task ON task_comments(task_id);
CREATE INDEX idx_task_labels_task ON task_labels(task_id);
CREATE INDEX idx_sprints_project ON sprints(project_id);

-- Transactional outbox and consumer deduplication, as in auth-service.
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
