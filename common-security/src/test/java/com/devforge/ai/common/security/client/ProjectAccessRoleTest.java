package com.devforge.ai.common.security.client;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devforge.ai.common.security.client.ProjectAccessClient.Access;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;

@DisplayName("Project role rules")
class ProjectAccessRoleTest {

  @Test
  @DisplayName("a VIEWER can read and nothing else")
  void viewerIsReadOnly() {
    assertThatCode(() -> ProjectAccessClient.requireRole("VIEWER", Access.READ)).doesNotThrowAnyException();
    assertThatThrownBy(() -> ProjectAccessClient.requireRole("VIEWER", Access.WRITE))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> ProjectAccessClient.requireRole("VIEWER", Access.ADMIN))
        .isInstanceOf(AccessDeniedException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"DEVELOPER", "TESTER"})
  @DisplayName("contributors write but do not administer")
  void contributorsWrite(String role) {
    assertThatCode(() -> ProjectAccessClient.requireRole(role, Access.WRITE)).doesNotThrowAnyException();
    assertThatThrownBy(() -> ProjectAccessClient.requireRole(role, Access.ADMIN))
        .isInstanceOf(AccessDeniedException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ADMIN", "TEAM_LEAD"})
  @DisplayName("admins and team leads do everything")
  void adminsAdminister(String role) {
    assertThatCode(() -> ProjectAccessClient.requireRole(role, Access.WRITE)).doesNotThrowAnyException();
    assertThatCode(() -> ProjectAccessClient.requireRole(role, Access.ADMIN)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("fails closed on a missing or unknown role")
  void unknownRoleGetsNothing() {
    // A role added to project-service later gets nothing until it is deliberately granted.
    assertThatThrownBy(() -> ProjectAccessClient.requireRole(null, Access.WRITE))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> ProjectAccessClient.requireRole("OWNER", Access.WRITE))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> ProjectAccessClient.requireRole("admin", Access.ADMIN))
        .isInstanceOf(AccessDeniedException.class);
  }
}
