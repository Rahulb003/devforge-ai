package com.devforge.ai.gateway;

import com.devforge.ai.common.exception.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

/**
 * A service the gateway could not reach, or that dropped the connection, is a 502 - the gateway's
 * own error - rather than the 500 the shared handler gave it, which read as the gateway failing.
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UpstreamErrorHandler {

  @ExceptionHandler(ResourceAccessException.class)
  public ResponseEntity<ApiError> upstreamUnreachable(ResourceAccessException ex, HttpServletRequest request) {
    var traceId = UUID.randomUUID().toString();
    log.warn("Upstream unreachable [traceId={}] on {} {}: {}", traceId, request.getMethod(),
        request.getRequestURI(), ex.getMessage());
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiError.builder()
        .timestamp(Instant.now())
        .status(HttpStatus.BAD_GATEWAY.value())
        .error(HttpStatus.BAD_GATEWAY.getReasonPhrase())
        .message("The service behind this request did not answer. Please try again.")
        .path(request.getRequestURI())
        .traceId(traceId)
        .details(Collections.emptyList())
        .build());
  }
}
