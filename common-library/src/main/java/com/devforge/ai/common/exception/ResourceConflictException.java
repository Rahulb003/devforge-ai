package com.devforge.ai.common.exception;

/**
 * Thrown when a request cannot be satisfied because it conflicts with existing state —
 * for example signing up with an email address that is already registered.
 *
 * <p>Maps to HTTP 409 Conflict, as distinct from a malformed request (400).
 */
public class ResourceConflictException extends RuntimeException {

  public ResourceConflictException(String message) {
    super(message);
  }

  public ResourceConflictException(String message, Throwable cause) {
    super(message, cause);
  }
}
