-- Tamper evidence for the audit log: each entry carries the hash of the one before it in its chain,
-- so editing, reordering or deleting a row breaks every hash after it. Without this, "append-only"
-- held only for the application; anyone with database access could rewrite history unnoticed.
--
-- One chain per project, plus one per organization for its organization-level entries, so a
-- project admin can verify their project's history without reading any other project's rows.
ALTER TABLE audit_log ADD COLUMN chain_key VARCHAR(80);
ALTER TABLE audit_log ADD COLUMN chain_sequence BIGINT;
ALTER TABLE audit_log ADD COLUMN previous_hash VARCHAR(64);
ALTER TABLE audit_log ADD COLUMN entry_hash VARCHAR(64);

-- Rows written before this migration have no chain; verification reports them as unchained
-- rather than pretending to vouch for them.
CREATE UNIQUE INDEX uq_audit_chain_position ON audit_log(chain_key, chain_sequence);

-- The latest position of each chain. Locked while an entry is appended, which serialises appends
-- to one chain across consumer threads, and compared on verification, which is what detects rows
-- removed from the end: a truncated chain is otherwise still internally consistent.
CREATE TABLE audit_chain_head (
    chain_key VARCHAR(80) PRIMARY KEY,
    last_sequence BIGINT NOT NULL,
    last_hash VARCHAR(64) NOT NULL
);
