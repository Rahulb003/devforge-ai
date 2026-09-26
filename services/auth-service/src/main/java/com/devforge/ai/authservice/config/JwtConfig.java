package com.devforge.ai.authservice.config;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Configuration
@ConfigurationProperties(prefix = "security.jwt")
public class JwtConfig {

  /** HS256 requires a key of at least 256 bits, i.e. 32 bytes. */
  private static final int MIN_SIGNING_KEY_BYTES = 32;
  private String issuer;
  private Duration accessTokenTtl = Duration.ofMinutes(15);
  private Duration refreshTokenTtl = Duration.ofDays(14);
  private String signingKey;
  private String cookieName = "DEVFORGE_REFRESH_TOKEN";

  /**
   * Lifetime of the MFA challenge token issued between password and second factor.
   * Short by design: it is a partially-authenticated credential.
   */
  private Duration mfaChallengeTtl = Duration.ofMinutes(5);

  /**
   * Whether the refresh cookie carries the {@code Secure} attribute. Defaults to {@code true};
   * only the local profile may turn it off, since plain-HTTP localhost cannot set secure cookies.
   */
  private boolean cookieSecure = true;

  /** {@code SameSite} attribute for the refresh cookie. */
  private String cookieSameSite = "Strict";

  public void setIssuer(String issuer) {
    this.issuer = issuer;
  }

  public void setAccessTokenTtl(Duration accessTokenTtl) {
    this.accessTokenTtl = accessTokenTtl;
  }

  public void setRefreshTokenTtl(Duration refreshTokenTtl) {
    this.refreshTokenTtl = refreshTokenTtl;
  }

  public void setSigningKey(String signingKey) {
    this.signingKey = signingKey;
  }

  public void setMfaChallengeTtl(Duration mfaChallengeTtl) {
    this.mfaChallengeTtl = mfaChallengeTtl;
  }

  public void setCookieName(String cookieName) {
    this.cookieName = cookieName;
  }

  public void setCookieSecure(boolean cookieSecure) {
    this.cookieSecure = cookieSecure;
  }

  public void setCookieSameSite(String cookieSameSite) {
    this.cookieSameSite = cookieSameSite;
  }

  /**
   * Rejects a missing or too-short signing key at startup.
   *
   * <p>Without this, a short key is only detected when the first token is signed — so the
   * service starts healthy, passes its readiness probe, takes traffic, and then fails every
   * login. A key below 256 bits also weakens HS256 itself. Failing during context
   * initialisation turns a silent production outage into a refused deployment.
   */
  @PostConstruct
  void validate() {
    if (signingKey == null || signingKey.isBlank()) {
      throw new IllegalStateException(
          "security.jwt.signing-key is not set. Provide DEVFORGE_JWT_SIGNING_KEY "
              + "(see .env.example). There is no default outside the local profile.");
    }
    var keyBytes = signingKey.getBytes(StandardCharsets.UTF_8).length;
    if (keyBytes < MIN_SIGNING_KEY_BYTES) {
      throw new IllegalStateException(
          "security.jwt.signing-key is too short for HS256: %d bytes, minimum %d. "
              .formatted(keyBytes, MIN_SIGNING_KEY_BYTES)
              + "Generate one with: openssl rand -base64 48");
    }
    if (issuer == null || issuer.isBlank()) {
      throw new IllegalStateException("security.jwt.issuer must be set; tokens are issuer-bound.");
    }
  }
}
