package com.devforge.ai.taskservice.exception;

import com.devforge.ai.common.exception.ApiError;
import com.devforge.ai.taskservice.client.ProjectAccessClient;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Reports an unreachable project-service as 503 rather than 500.
 *
 * <p>The distinction matters to a client: 503 says "this may work shortly, retry", while 500 says
 * "this request is broken, do not bother". Task access cannot be verified without project-service,
 * and failing closed is the only safe option — treating an unavailable authority as permission
 * would hand out access during an outage.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice
public class DependencyExceptionHandler {

  @ExceptionHandler(ProjectAccessClient.ProjectServiceUnavailableException.class)
  public ResponseEntity<ApiError> handleUnavailable(
      ProjectAccessClient.ProjectServiceUnavailableException ex, HttpServletRequest request) {
    var traceId = UUID.randomUUID().toString();
    log.error("Dependency unavailable [traceId={}] on {} {}",
        traceId, request.getMethod(), request.getRequestURI(), ex);

    var body = ApiError.builder()
        .timestamp(Instant.now())
        .status(HttpStatus.SERVICE_UNAVAILABLE.value())
        .error(HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase())
        .message(ex.getMessage())
        .path(request.getRequestURI())
        .traceId(traceId)
        .details(Collections.emptyList())
        .build();

    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
  }
}
