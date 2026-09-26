package com.devforge.ai.authservice.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the links that go into outbound email.
 *
 * <p>These were previously hard-coded to {@code https://app.devforge.ai/auth/verify}, which was
 * wrong twice: it ignored the configured application URL, so every environment mailed people a
 * link to production, and the path did not match any route in the application, so the link 404ed
 * wherever it was opened. Neither failure is visible until a real user clicks a real email.
 */
class MailLinksTest {

  private MailLinks links(String baseUrl) {
    return new MailLinks(baseUrl, "no-reply@devforge.ai");
  }

  @Test
  @DisplayName("verification links use the configured host")
  void verificationUsesConfiguredHost() {
    assertThat(links("http://localhost:4173").verifyEmailLink("abc"))
        .isEqualTo("http://localhost:4173/verify-email?token=abc");

    assertThat(links("https://app.example.com").verifyEmailLink("abc"))
        .startsWith("https://app.example.com/");
  }

  @Test
  @DisplayName("link paths match the application routes")
  void pathsMatchRoutes() {
    var mailLinks = links("http://localhost:4173");

    // These must stay in step with the routes declared in App.tsx.
    assertThat(mailLinks.verifyEmailLink("t")).contains("/verify-email?token=");
    assertThat(mailLinks.passwordResetLink("t")).contains("/reset-password?token=");
  }

  @Test
  @DisplayName("a trailing slash on the base URL does not produce a doubled path separator")
  void trailingSlashIsTrimmed() {
    assertThat(links("http://localhost:4173/").verifyEmailLink("abc"))
        .isEqualTo("http://localhost:4173/verify-email?token=abc")
        .doesNotContain("//verify-email");
  }

  @Test
  @DisplayName("tokens are URL-encoded")
  void tokensAreEncoded() {
    // Tokens are UUIDs today, but the link must not break if that ever changes to
    // something containing reserved characters.
    assertThat(links("http://localhost:4173").verifyEmailLink("a b&c=d"))
        .isEqualTo("http://localhost:4173/verify-email?token=a+b%26c%3Dd");
  }

  @Test
  @DisplayName("the from address is exposed for the sender to use")
  void fromAddressIsAvailable() {
    assertThat(links("http://localhost:4173").getFromAddress()).isEqualTo("no-reply@devforge.ai");
  }
}
