package com.devforge.ai.common.security;

import java.util.Set;
import java.util.UUID;

/**
 * The caller identified by a verified access token.
 *
 * <p>Carries identity only. It deliberately does <em>not</em> carry an organization or project
 * id: those determine what the caller may reach, and a value taken from a token the client
 * supplies is a value the client controls. Tenant scope is always resolved server-side from
 * membership records.
 *
 * @param id the user id, from the token subject
 * @param username display/login name
 * @param email the user's email address
 * @param roles global platform roles, e.g. {@code ROLE_ADMIN}
 */
public record AuthenticatedUser(UUID id, String username, String email, Set<String> roles) {

  public boolean hasRole(String role) {
    return roles != null && roles.contains(role);
  }

  /** True for platform administrators, who bypass per-tenant membership checks. */
  public boolean isPlatformAdmin() {
    return hasRole("ROLE_ADMIN");
  }
}
