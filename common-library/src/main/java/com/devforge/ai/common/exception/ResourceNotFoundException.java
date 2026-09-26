package com.devforge.ai.common.exception;

/**
 * Signals that a resource does not exist, or exists outside the caller's tenant.
 *
 * <p>The ambiguity is intentional and load-bearing. Reporting "forbidden" for a resource in
 * another tenant confirms that the id is real, which lets a caller enumerate other tenants'
 * identifiers. Both cases must look the same from outside.
 */
public class ResourceNotFoundException extends RuntimeException {
  public ResourceNotFoundException(String message) {
    super(message);
  }
}
