package com.devforge.ai.common.events;

/**
 * Canonical event type names.
 *
 * <p>Constants rather than free strings: a typo in a producer or a consumer's filter is otherwise
 * a silent no-op that only shows up as missing downstream behaviour. Documented in
 * docs/EVENT_CATALOG.md.
 */
public final class EventTypes {

  private EventTypes() {}

  // Identity
  public static final String USER_REGISTERED = "UserRegistered";
  public static final String USER_VERIFIED = "UserVerified";
  public static final String USER_LOGGED_IN = "UserLoggedIn";
  public static final String USER_PASSWORD_RESET = "UserPasswordReset";
  public static final String USER_MFA_ENABLED = "UserMfaEnabled";
  public static final String USER_MFA_DISABLED = "UserMfaDisabled";

  // Tenancy and projects
  public static final String ORGANIZATION_CREATED = "OrganizationCreated";
  public static final String ORGANIZATION_DELETED = "OrganizationDeleted";
  public static final String PROJECT_CREATED = "ProjectCreated";
  public static final String PROJECT_UPDATED = "ProjectUpdated";
  public static final String PROJECT_ARCHIVED = "ProjectArchived";
  public static final String PROJECT_DELETED = "ProjectDeleted";
  public static final String PROJECT_MEMBER_ADDED = "ProjectMemberAdded";
  public static final String PROJECT_MEMBER_REMOVED = "ProjectMemberRemoved";

  // Security
  public static final String SECURITY_ISSUE_DETECTED = "SecurityIssueDetected";
  public static final String REFRESH_TOKEN_REUSE_DETECTED = "RefreshTokenReuseDetected";
}
