package com.devforge.ai.authservice.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;

@Getter
@Configuration
@ConfigurationProperties(prefix = "security.jwt")
public class JwtConfig {
  private String issuer;
  private Duration accessTokenTtl = Duration.ofMinutes(15);
  private Duration refreshTokenTtl = Duration.ofDays(14);
  private String signingKey;
  private String cookieName = "DEVFORGE_REFRESH_TOKEN";

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

  public void setCookieName(String cookieName) {
    this.cookieName = cookieName;
  }

  public void setCookieSecure(boolean cookieSecure) {
    this.cookieSecure = cookieSecure;
  }

  public void setCookieSameSite(String cookieSameSite) {
    this.cookieSameSite = cookieSameSite;
  }
}
