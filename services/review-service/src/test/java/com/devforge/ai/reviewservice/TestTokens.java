package com.devforge.ai.reviewservice;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;

/**
 * Mints access tokens for tests, exactly as the auth service would.
 *
 * <p>Tests authenticate through the real filter chain with real signed tokens rather than
 * {@code @WithMockUser}: a mock principal bypasses token verification entirely, so the signature,
 * issuer and token-type checks would never run.

 */
final class TestTokens {

  static final String ISSUER = "devforge-ai-test";
  static final String SIGNING_KEY =
      "test-only-signing-key-that-is-long-enough-to-satisfy-hs256-256-bit-requirement";

  private TestTokens() {}

  static String accessToken(UUID userId) {
    return build(userId, "access", SIGNING_KEY, Instant.now().plus(15, ChronoUnit.MINUTES));
  }

  /** Structurally valid but the wrong type: must never authenticate an API call. */
  static String refreshToken(UUID userId) {
    return build(userId, "refresh", SIGNING_KEY, Instant.now().plus(14, ChronoUnit.DAYS));
  }

  static String wronglySignedToken(UUID userId) {
    return build(userId, "access",
        "an-entirely-different-key-that-is-also-long-enough-for-hs256-x",
        Instant.now().plus(15, ChronoUnit.MINUTES));
  }

  static String expiredToken(UUID userId) {
    return build(userId, "access", SIGNING_KEY, Instant.now().minus(1, ChronoUnit.MINUTES));
  }

  private static String build(UUID userId, String type, String secret, Instant expiry) {
    SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    return Jwts.builder()
        .subject(userId.toString())
        .issuer(ISSUER)
        .issuedAt(Date.from(Instant.now().minus(1, ChronoUnit.MINUTES)))
        .expiration(Date.from(expiry))
        .id(UUID.randomUUID().toString())
        .claim("typ", type)
        .claim("username", "user-" + userId.toString().substring(0, 8))
        .claim("email", userId.toString().substring(0, 8) + "@example.com")
        .claim("roles", List.of("ROLE_DEVELOPER"))
        .signWith(key, Jwts.SIG.HS256)
        .compact();
  }
}
