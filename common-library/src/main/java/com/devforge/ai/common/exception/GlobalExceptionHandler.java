package com.devforge.ai.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiError> handleValidationException(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    var errors = ex.getBindingResult().getFieldErrors().stream()
        .map(error -> error.getField() + ": " + error.getDefaultMessage())
        .toList();

    return build(HttpStatus.BAD_REQUEST, "Validation failed", request, errors);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ApiError> handleConstraintViolation(
      ConstraintViolationException ex, HttpServletRequest request) {
    var errors = ex.getConstraintViolations().stream()
        .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
        .toList();

    return build(HttpStatus.BAD_REQUEST, "Constraint violation", request, errors);
  }

  /**
   * A request that conflicts with existing state, such as a duplicate email on signup.
   *
   * <p>Without this, such failures fell through to the catch-all handler and were reported
   * as 500, telling the caller the server broke when in fact their request was invalid.
   */
  @ExceptionHandler(ResourceConflictException.class)
  public ResponseEntity<ApiError> handleConflict(
      ResourceConflictException ex, HttpServletRequest request) {
    return build(HttpStatus.CONFLICT, ex.getMessage(), request, Collections.emptyList());
  }

  /**
   * A resource that does not exist, or that belongs to another tenant.
   *
   * <p>Both map to 404. Answering 403 for the second case would confirm the id is real and let a
   * caller enumerate other tenants' resources.
   */
  @ExceptionHandler(ResourceNotFoundException.class)
  public ResponseEntity<ApiError> handleNotFound(
      ResourceNotFoundException ex, HttpServletRequest request) {
    return build(HttpStatus.NOT_FOUND, ex.getMessage(), request, Collections.emptyList());
  }

  // AccessDeniedException is handled in common-security's SecurityExceptionHandler.
  // It cannot live here: common-library is on the classpath of modules that have no
  // Spring Security dependency, and a handler method referencing a missing class fails
  // at advice-registration time.

  /**
   * Caller-supplied arguments that failed a domain check (expired token, unknown value, ...).
   *
   * <p>These messages are written by us for the caller, so echoing them is intentional.
   */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiError> handleIllegalArgument(
      IllegalArgumentException ex, HttpServletRequest request) {
    return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request, Collections.emptyList());
  }

  /**
   * Catch-all for genuinely unexpected failures.
   *
   * <p>The exception message is logged server-side against a correlation id and deliberately
   * NOT returned to the client. It previously was, which leaked internals — a real response
   * from this service once carried a raw Hibernate message naming an entity, its column and a
   * database id. The client gets only the correlation id to quote in a support request.
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleGenericException(Exception ex, HttpServletRequest request) {
    var traceId = UUID.randomUUID().toString();
    logger.error("Unhandled exception [traceId={}] on {} {}",
        traceId, request.getMethod(), request.getRequestURI(), ex);

    var apiError = ApiError.builder()
        .timestamp(Instant.now())
        .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
        .error(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase())
        .message("An unexpected error occurred")
        .path(request.getRequestURI())
        .traceId(traceId)
        .details(Collections.emptyList())
        .build();

    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(apiError);
  }

  private ResponseEntity<ApiError> build(
      HttpStatus status, String message, HttpServletRequest request, List<String> details) {
    var apiError = ApiError.builder()
        .timestamp(Instant.now())
        .status(status.value())
        .error(status.getReasonPhrase())
        .message(message)
        .path(request.getRequestURI())
        .traceId(UUID.randomUUID().toString())
        .details(details)
        .build();

    return ResponseEntity.status(status).body(apiError);
  }
}
