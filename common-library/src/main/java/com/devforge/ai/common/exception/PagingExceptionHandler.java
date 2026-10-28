package com.devforge.ai.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.data.web.config.PageableHandlerMethodArgumentResolverCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * The paging parameters every list endpoint accepts - ?page, ?size, ?sort - turned bad input into
 * 500s. Probing the read routes found two, on every paged list in every service:
 * {@code sort=password,desc} (no such property) and a page large enough that its offset overflowed
 * an int. Both are the caller's mistake, so both are 400s now, and the page size is capped.
 *
 * <p>Only where Spring Data is on the classpath: common-library also serves api-gateway, which has
 * no database and would fail to load an advice naming these exception types.
 */
@Slf4j
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnClass(name = "org.springframework.data.mapping.PropertyReferenceException")
public class PagingExceptionHandler {

  /** Lists are for reading a screen's worth; nothing in the app asks for more than 100 at once. */
  static final int MAX_PAGE_SIZE = 100;

  @Bean
  PageableHandlerMethodArgumentResolverCustomizer cappedPageSize() {
    return resolver -> resolver.setMaxPageSize(MAX_PAGE_SIZE);
  }

  @ExceptionHandler(PropertyReferenceException.class)
  public ResponseEntity<ApiError> unknownSortProperty(PropertyReferenceException ex, HttpServletRequest request) {
    // Not echoing the type name: it would describe the entity behind the endpoint.
    return error(HttpStatus.BAD_REQUEST, "Cannot sort by '" + ex.getPropertyName() + "'", request);
  }

  @ExceptionHandler(InvalidDataAccessApiUsageException.class)
  public ResponseEntity<ApiError> invalidDataAccess(InvalidDataAccessApiUsageException ex, HttpServletRequest request) {
    var message = ex.getMessage() == null ? "" : ex.getMessage();
    if (message.contains("Page offset exceeds")) {
      return error(HttpStatus.BAD_REQUEST, "That page is out of range", request);
    }
    // Anything else of this kind is a programming error, not the caller's: still a 500.
    var traceId = UUID.randomUUID().toString();
    log.error("Unhandled data access misuse [traceId={}] on {} {}", traceId, request.getMethod(),
        request.getRequestURI(), ex);
    return error(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request);
  }

  private static ResponseEntity<ApiError> error(HttpStatus status, String message, HttpServletRequest request) {
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
