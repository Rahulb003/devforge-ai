package com.devforge.ai.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Verifies access tokens issued by the auth service.
 *
 * <p>Verification is deliberately strict: signature, issuer, expiry <em>and</em> token type. The
 * {@code typ} check is what stops a long-lived refresh token being presented as an API credential.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenVerifier {

  /** Only access tokens may authenticate an API call. */
  private static final String TOKEN_TYPE_ACCESS = "access";

  private static final String CLAIM_TOKEN_TYPE = "typ";
  private static final String CLAIM_USERNAME = "username";
  private static final String CLAIM_EMAIL = "email";
  private static final String CLAIM_ROLES = "roles";

  private final JwtProperties properties;

  private SecretKey signingKey() {
    return Keys.hmacShaKeyFor(properties.getSigningKey().getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Verifies a bearer token and maps it to a caller.
   *
   * @return empty when the token is absent, malformed, expired, wrongly-typed or not ours.
   *     Callers must treat empty as unauthenticated and never fall back to a default identity.
   */
  public Optional<AuthenticatedUser> verify(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    try {
      Claims claims = Jwts.parser()
          .verifyWith(signingKey())
          .requireIssuer(properties.getIssuer())
          .build()
          .parseSignedClaims(token)
          .getPayload();

      if (!TOKEN_TYPE_ACCESS.equals(claims.get(CLAIM_TOKEN_TYPE, String.class))) {
        log.debug("Rejected token: wrong type claim.");
        return Optional.empty();
      }

      return Optional.of(new AuthenticatedUser(
          UUID.fromString(claims.getSubject()),
          claims.get(CLAIM_USERNAME, String.class),
          claims.get(CLAIM_EMAIL, String.class),
          extractRoles(claims)));
    } catch (JwtException | IllegalArgumentException ex) {
      // Logged at debug only: a bad token is an expected condition on a public endpoint,
      // and the value itself must never reach the logs.
      log.debug("Rejected token: {}", ex.getMessage());
      return Optional.empty();
    }
  }

  @SuppressWarnings("unchecked")
  private Set<String> extractRoles(Claims claims) {
    var raw = claims.get(CLAIM_ROLES);
    if (raw instanceof List<?> list) {
      return list.stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet());
    }
    return Set.of();
  }
}
