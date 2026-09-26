package com.devforge.ai.projectservice.model;

/** A user's role within a single project. */
public enum ProjectRole {
  ADMIN,
  TEAM_LEAD,
  DEVELOPER,
  TESTER,
  VIEWER;

  /** Whether this role may change project settings or membership. */
  public boolean canAdminister() {
    return this == ADMIN || this == TEAM_LEAD;
  }

  /** Whether this role may create or modify project content. VIEWER is read-only. */
  public boolean canWrite() {
    return this != VIEWER;
  }
}
