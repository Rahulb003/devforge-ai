package com.devforge.ai.documentationservice.exception;

import com.devforge.ai.common.exception.ApiError;
import com.devforge.ai.common.git.GitContentClient;
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
 * <p>The distinction matters here more than usual. Documentation generated without reading the
 * code would describe a repository with no API and no public surface — a confident, wrong document,
 * which is worse than none because the reader cannot tell. 503 says "retry"; an empty document
 * would say "there is nothing here".
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
