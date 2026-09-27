package com.devforge.ai.taskservice.service;

import com.devforge.ai.common.security.AuthenticatedUser;
import com.devforge.ai.taskservice.client.ProjectAccessClient;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolves the caller and checks they may work in a project.
 *
 * <p>Every entry point into task data goes through here. The check is delegated to
 * project-service, which owns project membership — see {@link ProjectAccessClient} for why the
 * data is not replicated locally.
 */
@Service
@RequiredArgsConstructor
public class TaskAccessService {

  private final ProjectAccessClient projectAccessClient;

  public AuthenticatedUser requireCurrentUser() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.isAuthenticated()
        && authentication.getPrincipal() instanceof AuthenticatedUser user) {
      return user;
    }
    throw new org.springframework.security.access.AccessDeniedException("Not authenticated");
  }

  /**
   * Asserts the caller may work in the project.
   *
   * <p>Forwards the caller's own token rather than a service credential on purpose: the decision
   * must be made about the user who made the request, not about this service. A service account
   * would grant task-service blanket access to every project and reduce this to a check nobody
   * enforces.
   */
  public void requireProjectAccess(UUID organizationId, UUID projectId) {
    projectAccessClient.requireProjectAccess(organizationId, projectId, currentBearerToken());
  }

  private String currentBearerToken() {
    var attributes = RequestContextHolder.getRequestAttributes();
    if (attributes instanceof ServletRequestAttributes servletAttributes) {
      HttpServletRequest request = servletAttributes.getRequest();
      var header = request.getHeader(HttpHeaders.AUTHORIZATION);
      if (header != null && !header.isBlank()) {
        return header;
      }
    }
    // The filter chain already rejected anonymous requests, so reaching here means the token
    // was consumed somewhere it should not have been.
    throw new org.springframework.security.access.AccessDeniedException(
        "No bearer token on the current request");
  }
}
