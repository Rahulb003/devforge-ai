package com.devforge.ai.common.security;

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
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Maps Spring Security failures raised inside a controller call to correct HTTP statuses.
 *
 * <p>Lives in common-security rather than alongside the other handlers in common-library, because
 * common-library is on the classpath of modules with no Spring Security dependency, and a
 * {@code @ExceptionHandler} whose parameter type is missing fails when the advice is registered.
 *
 * <p>Ordered ahead of the general advice so these are not swallowed by its catch-all
 * {@code Exception} handler and reported as 500.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice
public class SecurityExceptionHandler {

  /**
   * The caller is authenticated but lacks the required role.
   *
   * <p>403 is right here only because the caller already knows the resource exists — they are
   * inside the tenant. Cross-tenant access is reported as 404 by ResourceNotFoundException, so
   * that ids cannot be probed.
   */
  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ApiError> handleAccessDenied(
      AccessDeniedException ex, HttpServletRequest request) {
    return build(HttpStatus.FORBIDDEN, "Access denied", request);
  }

  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<ApiError> handleAuthentication(
      AuthenticationException ex, HttpServletRequest request) {
    return build(HttpStatus.UNAUTHORIZED, "Authentication required", request);
  }

  private ResponseEntity<ApiError> build(
      HttpStatus status, String message, HttpServletRequest request) {
    // The exception message is not echoed: it can name roles, beans and expressions that
    // describe the authorization model.
    var apiError = ApiError.builder()
        .timestamp(Instant.now())
        .status(status.value())
        .error(status.getReasonPhrase())
        .message(message)
        .path(request.getRequestURI())
        .traceId(UUID.randomUUID().toString())
        .details(Collections.emptyList())
        .build();
    return ResponseEntity.status(status).body(apiError);
  }
}
