-- Organizations are the tenant boundary. Every other row in this service is
-- reachable only through an organization the caller belongs to.
CREATE TABLE organizations (
    id UUID PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    -- URL-safe identifier, unique platform-wide.
    slug VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(1000),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP,
    version BIGINT NOT NULL
);

-- Membership is the authorization source of truth. A tenant id supplied by a
-- client is never trusted; access is derived from a row in this table.
CREATE TABLE organization_members (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    user_id UUID NOT NULL,
    role VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP,
    version BIGINT NOT NULL,
    CONSTRAINT fk_org_members_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    -- One membership row per user per organization: duplicates would make the
    -- effective role ambiguous, and "highest wins" is not something to leave to
    -- row ordering.
    CONSTRAINT uq_org_members_org_user UNIQUE (organization_id, user_id)
);

CREATE TABLE projects (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    name VARCHAR(150) NOT NULL,
    project_key VARCHAR(20) NOT NULL,
    description VARCHAR(2000),
    status VARCHAR(50) NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP,
    version BIGINT NOT NULL,
    CONSTRAINT fk_projects_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    -- Scoped to the organization, not global: two tenants may both want "CORE".
    CONSTRAINT uq_projects_org_key UNIQUE (organization_id, project_key)
);

CREATE TABLE project_members (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    user_id UUID NOT NULL,
    role VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP,
    version BIGINT NOT NULL,
    CONSTRAINT fk_project_members_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT uq_project_members_project_user UNIQUE (project_id, user_id)
);

-- Membership lookups run on every authorization decision, so they are indexed
-- by user as well as by parent.
CREATE INDEX idx_org_members_user_id ON organization_members(user_id);
CREATE INDEX idx_org_members_org_id ON organization_members(organization_id);
CREATE INDEX idx_projects_org_id ON projects(organization_id);
CREATE INDEX idx_projects_status ON projects(status);
CREATE INDEX idx_project_members_user_id ON project_members(user_id);
CREATE INDEX idx_project_members_project_id ON project_members(project_id);
