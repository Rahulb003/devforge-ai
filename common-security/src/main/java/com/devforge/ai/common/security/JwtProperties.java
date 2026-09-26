package com.devforge.ai.common.security;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Token-verification settings shared by every resource server.
 *
 * <p>Bound to the same {@code security.jwt.*} keys the auth service uses to issue tokens, so a
 * mismatch between issuer and verifier is a configuration error rather than a silent
 * authentication failure.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "security.jwt")
public class JwtProperties {

  /** HS256 requires at least 256 bits. */
  private static final int MIN_SIGNING_KEY_BYTES = 32;

  private String issuer;
  private String signingKey;

  /**
   * Fails startup on a missing or weak key, matching the auth service.
   *
   * <p>A resource server that starts with an invalid key would reject every request as
   * unauthenticated while still reporting healthy — an outage that looks like a client bug.
   */
  @PostConstruct
  void validate() {
    if (signingKey == null || signingKey.isBlank()) {
      throw new IllegalStateException(
          "security.jwt.signing-key is not set. Provide DEVFORGE_JWT_SIGNING_KEY; it must match "
              + "the key the auth service signs with.");
    }
    var keyBytes = signingKey.getBytes(StandardCharsets.UTF_8).length;
    if (keyBytes < MIN_SIGNING_KEY_BYTES) {
      throw new IllegalStateException(
          "security.jwt.signing-key is too short for HS256: %d bytes, minimum %d."
              .formatted(keyBytes, MIN_SIGNING_KEY_BYTES));
    }
    if (issuer == null || issuer.isBlank()) {
      throw new IllegalStateException("security.jwt.issuer must be set; tokens are issuer-bound.");
    }
  }
}
