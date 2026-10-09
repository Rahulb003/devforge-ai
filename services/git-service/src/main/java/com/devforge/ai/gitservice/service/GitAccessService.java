package com.devforge.ai.gitservice.service;

import com.devforge.ai.common.security.AuthenticatedUser;
import com.devforge.ai.common.security.client.ProjectAccessClient;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolves the caller and checks they may work in a project.
 *
 * <p>Every entry point into repository data goes through here. Project membership belongs to
 * project-service, and is asked for rather than replicated — see {@link ProjectAccessClient}.
 */
@Service
@RequiredArgsConstructor
public class GitAccessService {

  private final ProjectAccessClient projectAccessClient;

  public AuthenticatedUser requireCurrentUser() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.isAuthenticated()
        && authentication.getPrincipal() instanceof AuthenticatedUser user) {
      return user;
    }
    throw new AccessDeniedException("Not authenticated");
  }

  /**
   * Asserts the caller may work in the project.
   *
   * <p>Forwards the caller's own token rather than a service credential: the decision must be made
   * about the user who made the request, not about this service. A service account would grant
   * git-service blanket access to every project's repositories.
   */
  public void requireProjectAccess(UUID organizationId, UUID projectId) {
    requireProjectAccess(organizationId, projectId, ProjectAccessClient.Access.READ);
  }

  /** At a given level: WRITE for commits, branches and pull requests; ADMIN for deletion. */
  public void requireProjectAccess(UUID organizationId, UUID projectId, ProjectAccessClient.Access level) {
    projectAccessClient.requireProjectAccess(organizationId, projectId, currentBearerToken(), level);
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
    // The filter chain already rejected anonymous requests, so reaching here means the token was
    // consumed somewhere it should not have been.
    throw new AccessDeniedException("No bearer token on the current request");
  }
}
