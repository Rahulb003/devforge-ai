-- Organizations could only ever contain their creator: there was no way to add anyone.

-- Who a member is, as shown to the rest of the organization. Users live in auth-service's database,
-- which this service must not read, so the name and address are taken from the member's own
-- verified token when they join. Null for rows from before this column existed.
ALTER TABLE organization_members ADD COLUMN username VARCHAR(50);
ALTER TABLE organization_members ADD COLUMN email VARCHAR(255);

-- An invitation to an email address. Accepted only by a signed-in account whose token carries that
-- address as verified, so registering an account under someone else's address does not let anyone
-- take their invitations. Created the same way whether or not an account exists for the address,
-- so inviting cannot be used to discover who is registered.
CREATE TABLE organization_invitations (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    -- Stored lower-cased; compared lower-cased.
    email VARCHAR(255) NOT NULL,
    role VARCHAR(50) NOT NULL,
    invited_by UUID NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    accepted_at TIMESTAMP,
    declined_at TIMESTAMP,
    revoked_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP,
    version BIGINT NOT NULL,
    CONSTRAINT fk_invitations_org FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE
);

CREATE INDEX idx_invitations_email ON organization_invitations(email);
CREATE INDEX idx_invitations_org ON organization_invitations(organization_id);
