package com.devforge.ai.authservice.config;

import com.devforge.ai.common.exception.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * A refused {@code @PreAuthorize} is a 403, not a 500.
 *
 * <p>The other services get this from common-security's SecurityExceptionHandler; auth-service has
 * its own security configuration and does not depend on that module, so the denial fell through to
 * the general catch-all and was reported as an internal error. Found by the first admin-only
 * endpoint here, the audit chain verification.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice
public class AuthSecurityExceptionHandler {

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ApiError> handleAccessDenied(
      AccessDeniedException ex, HttpServletRequest request) {
    // The exception message is not echoed: it can describe the authorization rules.
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.builder()
        .timestamp(Instant.now())
        .status(HttpStatus.FORBIDDEN.value())
        .error(HttpStatus.FORBIDDEN.getReasonPhrase())
        .message("Access denied")
        .path(request.getRequestURI())
        .traceId(UUID.randomUUID().toString())
        .details(Collections.emptyList())
        .build());
  }

  /** Here rather than in the catch-all, which would report a dependency's outage as our 500. */
  @ExceptionHandler(com.devforge.ai.authservice.client.OrganizationMembershipClient.DependencyUnavailableException.class)
  public ResponseEntity<ApiError> handleDependencyUnavailable(RuntimeException ex, HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiError.builder()
        .timestamp(Instant.now())
        .status(HttpStatus.SERVICE_UNAVAILABLE.value())
        .error(HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase())
        .message(ex.getMessage())
        .path(request.getRequestURI())
        .traceId(UUID.randomUUID().toString())
        .details(Collections.emptyList())
        .build());
  }
}
