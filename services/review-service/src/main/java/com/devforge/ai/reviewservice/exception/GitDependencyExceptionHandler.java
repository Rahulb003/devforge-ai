package com.devforge.ai.reviewservice.exception;

import com.devforge.ai.common.exception.ApiError;
import com.devforge.ai.reviewservice.client.GitContentClient;
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
 * Reports unreadable repository content as 503 rather than 500.
 *
 * <p>The distinction matters here more than usual. A review that cannot read the code must not
 * report "no problems found" — that is indistinguishable from a clean repository and would be
 * trusted as a pass. 503 says "retry", 500 says "this is broken", and silence would say "all
 * clear", which is the one answer that must never be wrong.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice
public class GitDependencyExceptionHandler {

  @ExceptionHandler(GitContentClient.GitServiceUnavailableException.class)
  public ResponseEntity<ApiError> handleUnavailable(
      GitContentClient.GitServiceUnavailableException ex, HttpServletRequest request) {
    var traceId = UUID.randomUUID().toString();
    log.error("git-service unavailable [traceId={}] on {} {}",
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
