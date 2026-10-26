-- Who has a channel open right now: one row per open stream, on whichever instance holds it.
-- Rows are kept alive by a heartbeat and expire on their own, so an instance that dies without
-- cleaning up does not leave its users "here" for ever.
CREATE TABLE chat_presence (
    connection_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    user_id UUID NOT NULL,
    user_name VARCHAR(100) NOT NULL,
    instance_id VARCHAR(64) NOT NULL,
    last_seen TIMESTAMP NOT NULL
);

CREATE INDEX idx_chat_presence_project_seen ON chat_presence(project_id, last_seen);
