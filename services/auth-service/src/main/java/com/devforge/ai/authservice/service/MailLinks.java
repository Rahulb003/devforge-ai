package com.devforge.ai.authservice.service;

import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds the links that go into outbound email.
 *
 * <p>Centralised because they were previously hard-coded inside the mail sender, pointing at
 * {@code https://app.devforge.ai/auth/verify}. That was wrong twice over: the host ignored the
 * configured application URL, so a local or staging deployment mailed people a link to production;
 * and the paths did not match the application's routes, so the links 404ed wherever they were
 * opened.
 */
@Getter
@Component
public class MailLinks {

  /** Base URL of the web application, without a trailing slash. */
  private final String baseUrl;

  private final String fromAddress;

  public MailLinks(
      @Value("${devforge.app.base-url}") String baseUrl,
      @Value("${devforge.mail.from:no-reply@devforge.ai}") String fromAddress) {
    // A trailing slash would produce "//verify-email" and break the route match.
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.fromAddress = fromAddress;
  }

  /** Must match the frontend route in App.tsx. */
  public String verifyEmailLink(String token) {
    return "%s/verify-email?token=%s".formatted(baseUrl, encode(token));
  }

  /** Must match the frontend route in App.tsx. */
  public String passwordResetLink(String token) {
    return "%s/reset-password?token=%s".formatted(baseUrl, encode(token));
  }

  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
