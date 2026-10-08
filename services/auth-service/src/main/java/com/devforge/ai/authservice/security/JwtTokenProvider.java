package com.devforge.ai.authservice.security;

import com.devforge.ai.authservice.config.JwtConfig;
import com.devforge.ai.authservice.entity.UserEntity;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.crypto.SecretKey;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

/**
 * Issues and verifies the JSON Web Tokens used by the auth service.
 *
 * <p>Access tokens and refresh tokens are both signed with the same key but carry a distinct
 * {@code typ} claim. Verification is type-aware so a refresh token can never be replayed as an
 * access token (and vice versa), which would otherwise hand an attacker a 14-day bearer token.
 */
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

  /** Must match the name api-gateway reads. */
  public static final String ACCESS_COOKIE_NAME = "DEVFORGE_ACCESS_TOKEN";

  /** Value of the {@code typ} claim on short-lived API access tokens. */
  public static final String TOKEN_TYPE_ACCESS = "access";

  /** Value of the {@code typ} claim on long-lived refresh tokens. */
  public static final String TOKEN_TYPE_REFRESH = "refresh";

  /**
   * Value of the {@code typ} claim on the short-lived token issued between a correct password
   * and a verified second factor.
   *
   * <p>It authenticates nothing on its own: {@code validateToken} requires the access type, so
   * presenting this at an API endpoint fails. It only identifies which half-authenticated login
   * a submitted MFA code belongs to.
   */
  public static final String TOKEN_TYPE_MFA_CHALLENGE = "mfa";

  private static final String CLAIM_TOKEN_TYPE = "typ";
  private static final String CLAIM_USERNAME = "username";
  private static final String CLAIM_EMAIL = "email";
  private static final String CLAIM_ROLES = "roles";

  @Getter
  private final JwtConfig jwtConfig;

  /**
   * Issues the short-lived token that carries a login from "password accepted" to "second factor
   * verified".
   *
   * <p>Deliberately brief: it is a partially-authenticated credential, so the window in which a
   * captured one is useful must be small.
   */
  public String createMfaChallengeToken(UserEntity user) {
    return buildToken(
        user.getId(),
        user.getUsername(),
        user.getEmail(),
        null,
        TOKEN_TYPE_MFA_CHALLENGE,
        jwtConfig.getMfaChallengeTtl());
  }

  public java.time.Duration getAccessTokenTtl() {
    return jwtConfig.getAccessTokenTtl();
  }

  public java.time.Duration getRefreshTokenTtl() {
    return jwtConfig.getRefreshTokenTtl();
  }

  private SecretKey getSigningKey() {
    return Keys.hmacShaKeyFor(jwtConfig.getSigningKey().getBytes(StandardCharsets.UTF_8));
  }

  public String createAccessToken(Authentication authentication) {
    var principal = (UserPrincipal) authentication.getPrincipal();
    var roles = principal.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .collect(Collectors.toList());

    return buildToken(
        principal.getId(),
        principal.getUsername(),
        principal.getEmail(),
        roles,
        TOKEN_TYPE_ACCESS,
        jwtConfig.getAccessTokenTtl());
  }

  public String createRefreshToken(UserEntity user) {
    return buildToken(
        user.getId(),
        user.getUsername(),
        user.getEmail(),
        null,
        TOKEN_TYPE_REFRESH,
        jwtConfig.getRefreshTokenTtl());
  }

  private String buildToken(
      UUID subject,
      String username,
      String email,
      List<String> roles,
      String tokenType,
      java.time.Duration ttl) {
    var now = Instant.now();
    var builder = Jwts.builder()
        .subject(subject.toString())
        .issuer(jwtConfig.getIssuer())
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(ttl)))
        .id(UUID.randomUUID().toString())
        .claim(CLAIM_TOKEN_TYPE, tokenType)
        .claim(CLAIM_USERNAME, username)
        .claim(CLAIM_EMAIL, email);

    if (roles != null) {
      builder.claim(CLAIM_ROLES, roles);
    }

    return builder.signWith(getSigningKey(), Jwts.SIG.HS256).compact();
  }

  /** Returns {@code true} only for a structurally valid, unexpired <em>access</em> token. */
  public boolean validateToken(String token) {
    return validateToken(token, TOKEN_TYPE_ACCESS);
  }

  /**
   * Verifies signature, issuer and expiry, and additionally requires the token to carry the
   * expected {@code typ} claim.
   */
  public boolean validateToken(String token, String expectedType) {
    try {
      var claims = parseClaims(token);
      return expectedType.equals(claims.get(CLAIM_TOKEN_TYPE, String.class));
    } catch (JwtException | IllegalArgumentException ex) {
      return false;
    }
  }

  public UUID getUserIdFromToken(String token) {
    return UUID.fromString(parseClaims(token).getSubject());
  }

  public String getUsernameFromToken(String token) {
    return parseClaims(token).get(CLAIM_USERNAME, String.class);
  }

  /**
   * Parses and cryptographically verifies a token.
   *
   * @throws JwtException if the signature, issuer or expiry check fails.
   */
  public Claims parseClaims(String token) {
    return Jwts.parser()
        .verifyWith(getSigningKey())
        .requireIssuer(jwtConfig.getIssuer())
        .build()
        .parseSignedClaims(token)
        .getPayload();
  }

  public void addRefreshTokenCookie(HttpServletResponse response, String token) {
    // jakarta.servlet.http.Cookie has no SameSite support, so the header is built explicitly.
    var cookie = ResponseCookie.from(jwtConfig.getCookieName(), token)
        .httpOnly(true)
        .secure(jwtConfig.isCookieSecure())
        .path("/")
        .maxAge(jwtConfig.getRefreshTokenTtl())
        .sameSite(jwtConfig.getCookieSameSite())
        .build();
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
  }

  /**
   * The access token as an HttpOnly cookie, for the browser.
   *
   * <p>Script cannot read it, so an XSS can act inside the page but cannot carry the token away and
   * use it elsewhere. The gateway turns it into the Authorization header, so services behind it see
   * a bearer token exactly as before. SameSite and the gateway's custom-header check together stop
   * another site from riding it.
   */
  public void addAccessTokenCookie(HttpServletResponse response, String token) {
    response.addHeader(HttpHeaders.SET_COOKIE, accessCookie(token, jwtConfig.getAccessTokenTtl()).toString());
  }

  public void clearAccessTokenCookie(HttpServletResponse response) {
    response.addHeader(HttpHeaders.SET_COOKIE, accessCookie("", java.time.Duration.ZERO).toString());
  }

  private ResponseCookie accessCookie(String value, java.time.Duration maxAge) {
    return ResponseCookie.from(ACCESS_COOKIE_NAME, value)
        .httpOnly(true)
        .secure(jwtConfig.isCookieSecure())
        .path("/api")
        .maxAge(maxAge)
        .sameSite(jwtConfig.getCookieSameSite())
        .build();
  }

  public void clearRefreshTokenCookie(HttpServletResponse response) {
    var cookie = ResponseCookie.from(jwtConfig.getCookieName(), "")
        .httpOnly(true)
        .secure(jwtConfig.isCookieSecure())
        .path("/")
        .maxAge(0)
        .sameSite(jwtConfig.getCookieSameSite())
        .build();
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
  }

  public void handleOAuthSuccess(HttpServletResponse response, Authentication authentication) {
    var accessToken = createAccessToken(authentication);
    var user = (UserPrincipal) authentication.getPrincipal();
    var refreshToken = createRefreshToken(user.toEntity());
    addRefreshTokenCookie(response, refreshToken);
    response.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
    response.setStatus(HttpServletResponse.SC_OK);
  }
}
