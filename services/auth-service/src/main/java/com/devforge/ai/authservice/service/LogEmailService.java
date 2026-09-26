package com.devforge.ai.authservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Development mail provider: captures messages instead of sending them.
 *
 * <p>Selected by {@code devforge.mail.provider=log}. Every message goes to the log <em>and</em> to
 * {@link DevMailbox}, which the dev mailbox endpoint reads, so a verification link is reachable
 * from the browser rather than only from whichever console window the service happens to own.
 *
 * <p>A development provider in the sense of section 125: explicitly named, selected by
 * configuration, and replaced by declaring {@code devforge.mail.provider=smtp}. It is not a mock
 * presented as production behaviour — it prints a warning on every use so nobody can mistake a
 * captured message for a delivered one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "devforge.mail.provider", havingValue = "log")
public class LogEmailService implements EmailService {

  private final MailLinks links;
  private final DevMailbox mailbox;

  @Override
  public void sendEmailVerification(String email, String token) {
    var url = links.verifyEmailLink(token);
    capture(email, "Verify your DevForge AI email",
        "Confirm your email address to activate your account:\n\n" + url, url);
  }

  @Override
  public void sendPasswordReset(String email, String token) {
    var url = links.passwordResetLink(token);
    capture(email, "Reset your DevForge AI password",
        "Set a new password:\n\n" + url, url);
  }

  @Override
  public void sendWelcomeEmail(String email) {
    capture(email, "Welcome to DevForge AI", "Your DevForge AI account is ready.", links.getBaseUrl());
  }

  private void capture(String to, String subject, String body, String actionUrl) {
    mailbox.capture(to, subject, body, actionUrl);

    // WARN rather than INFO: this must be visible at default log levels, because an operator
    // who believes mail is being delivered when it is not has a silent outage.
    log.warn("""

        ==================== EMAIL NOT SENT (dev provider) ====================
        To:      {}
        Subject: {}
        Link:    {}

        devforge.mail.provider=log, so nothing was delivered.
        Open the link above, or browse captured mail at /api/v1/dev/mailbox.
        Set devforge.mail.provider=smtp with MAIL_* configured to send for real.
        =======================================================================
        """, to, subject, actionUrl);
  }
}
