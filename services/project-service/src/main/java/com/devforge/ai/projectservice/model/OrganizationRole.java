package com.devforge.ai.projectservice.model;

/** A user's role within an organization. Ordered from most to least privileged. */
public enum OrganizationRole {
  /** Full control, including deleting the organization. Cannot be removed by an ADMIN. */
  OWNER,
  /** Manages members and projects, but cannot delete the organization. */
  ADMIN,
  /** Belongs to the organization; project access is granted per project. */
  MEMBER;

  public boolean canManageProjects() {
    return this == OWNER || this == ADMIN;
  }

  public boolean canManageMembers() {
    return this == OWNER || this == ADMIN;
  }

  public boolean canDeleteOrganization() {
    return this == OWNER;
  }
}
