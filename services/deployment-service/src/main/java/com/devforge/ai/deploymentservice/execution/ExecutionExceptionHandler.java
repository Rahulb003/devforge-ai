package com.devforge.ai.deploymentservice.execution;

import com.devforge.ai.common.exception.ApiError;
import com.devforge.ai.deploymentservice.execution.ExecutionService.QuotaExceededException;
import com.devforge.ai.deploymentservice.sandbox.Sandbox.SandboxUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice
public class ExecutionExceptionHandler {

  @ExceptionHandler(SandboxUnavailableException.class)
  public ResponseEntity<ApiError> unavailable(SandboxUnavailableException ex, HttpServletRequest request) {
    return build(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), request);
  }

  @ExceptionHandler(QuotaExceededException.class)
  public ResponseEntity<ApiError> quota(QuotaExceededException ex, HttpServletRequest request) {
    return build(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), request);
  }

  private static ResponseEntity<ApiError> build(HttpStatus status, String message, HttpServletRequest request) {
    return ResponseEntity.status(status).body(ApiError.builder()
        .timestamp(Instant.now())
        .status(status.value())
        .error(status.getReasonPhrase())
        .message(message)
        .path(request.getRequestURI())
        .traceId(UUID.randomUUID().toString())
        .details(Collections.emptyList())
        .build());
  }
}
