-- Tamper evidence for the account audit log, as analytics-service has for project changes: each
-- entry carries the hash of the one before it, so editing, reordering or deleting a row breaks every
-- hash after it. One chain for the whole table - these events belong to accounts, not tenants.
ALTER TABLE audit_logs ADD COLUMN chain_sequence BIGINT;
ALTER TABLE audit_logs ADD COLUMN previous_hash VARCHAR(64);
ALTER TABLE audit_logs ADD COLUMN entry_hash VARCHAR(64);

-- Rows from before this migration have no position and are reported as unchained.
CREATE UNIQUE INDEX uq_audit_logs_chain_sequence ON audit_logs(chain_sequence);

-- Where the chain ends. Locked while an entry is appended, so concurrent writers take turns, and
-- compared on verification, which is what shows rows deleted from the end.
CREATE TABLE audit_chain_head (
    chain_key VARCHAR(40) PRIMARY KEY,
    last_sequence BIGINT NOT NULL,
    last_hash VARCHAR(64) NOT NULL
);
