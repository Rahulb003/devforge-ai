package com.devforge.ai.projectservice;

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
 * {@code @WithMockUser}. A mock principal would bypass {@code JwtTokenVerifier} entirely, so the
 * signature, issuer and token-type checks — the things most worth testing — would never run.
 */
final class TestTokens {

  static final String ISSUER = "devforge-ai-test";
  static final String SIGNING_KEY =
      "test-only-signing-key-that-is-long-enough-to-satisfy-hs256-256-bit-requirement";

  private TestTokens() {}

  private static SecretKey key(String secret) {
    return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
  }

  /** A valid access token for the given user. */
  static String accessToken(UUID userId) {
    return accessToken(userId, List.of("ROLE_DEVELOPER"));
  }

  static String accessToken(UUID userId, List<String> roles) {
    return build(userId, roles, "access", ISSUER, SIGNING_KEY, Instant.now().plus(15, ChronoUnit.MINUTES));
  }

  /** A refresh token: structurally valid, but must never authenticate an API call. */
  static String refreshToken(UUID userId) {
    return build(userId, null, "refresh", ISSUER, SIGNING_KEY, Instant.now().plus(14, ChronoUnit.DAYS));
  }

  /** Correctly shaped but signed with the wrong key, as a forged token would be. */
  static String wronglySignedToken(UUID userId) {
    return build(userId, List.of("ROLE_DEVELOPER"), "access", ISSUER,
        "an-entirely-different-key-that-is-also-long-enough-for-hs256-x",
        Instant.now().plus(15, ChronoUnit.MINUTES));
  }

  /** Issued by someone else; the issuer check must reject it. */
  static String wrongIssuerToken(UUID userId) {
    return build(userId, List.of("ROLE_DEVELOPER"), "access", "some-other-issuer", SIGNING_KEY,
        Instant.now().plus(15, ChronoUnit.MINUTES));
  }

  static String expiredToken(UUID userId) {
    return build(userId, List.of("ROLE_DEVELOPER"), "access", ISSUER, SIGNING_KEY,
        Instant.now().minus(1, ChronoUnit.MINUTES));
  }

  private static String build(
      UUID userId, List<String> roles, String type, String issuer, String secret, Instant expiry) {
    var builder = Jwts.builder()
        .subject(userId.toString())
        .issuer(issuer)
        .issuedAt(Date.from(Instant.now().minus(1, ChronoUnit.MINUTES)))
        .expiration(Date.from(expiry))
        .id(UUID.randomUUID().toString())
        .claim("typ", type)
        .claim("username", "user-" + userId.toString().substring(0, 8))
        .claim("email", userId.toString().substring(0, 8) + "@example.com");
    if (roles != null) {
      builder.claim("roles", roles);
    }
    return builder.signWith(key(secret), Jwts.SIG.HS256).compact();
  }
}
