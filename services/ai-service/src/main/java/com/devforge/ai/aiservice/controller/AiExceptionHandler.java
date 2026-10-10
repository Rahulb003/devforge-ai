package com.devforge.ai.aiservice.controller;

import com.devforge.ai.aiservice.model.ModelClient.ModelUnavailableException;
import com.devforge.ai.aiservice.service.ExplainService.RateLimitedException;
import com.devforge.ai.common.exception.ApiError;
import com.devforge.ai.common.git.GitContentClient.GitServiceUnavailableException;
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

/** The AI-specific failures, as statuses a client can act on. Messages are written for the user. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice
public class AiExceptionHandler {

  @ExceptionHandler(ModelUnavailableException.class)
  public ResponseEntity<ApiError> unavailable(ModelUnavailableException ex, HttpServletRequest request) {
    return build(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), request);
  }

  @ExceptionHandler(GitServiceUnavailableException.class)
  public ResponseEntity<ApiError> gitUnavailable(GitServiceUnavailableException ex, HttpServletRequest request) {
    return build(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), request);
  }

  @ExceptionHandler(RateLimitedException.class)
  public ResponseEntity<ApiError> limited(RateLimitedException ex, HttpServletRequest request) {
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
