package com.devforge.ai.authservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Sends mail over SMTP.
 *
 * <p>Selected by {@code devforge.mail.provider=smtp} rather than by Spring profile. Tying the
 * provider to the profile meant the standalone profile could never send real mail, even when an
 * operator had perfectly good SMTP credentials to hand — the only way to test delivery was to
 * abandon the profile that made the app runnable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "devforge.mail.provider", havingValue = "smtp", matchIfMissing = true)
public class SmtpEmailService implements EmailService {

  private final JavaMailSender mailSender;
  private final MailLinks links;

  @Override
  public void sendEmailVerification(String email, String token) {
    send(
        email,
        "Verify your DevForge AI email",
        """
        Welcome to DevForge AI.

        Confirm your email address to activate your account:

        %s

        This link can be used once and expires in 24 hours.
        If you did not create an account, you can ignore this message.
        """.formatted(links.verifyEmailLink(token)));
  }

  @Override
  public void sendPasswordReset(String email, String token) {
    send(
        email,
        "Reset your DevForge AI password",
        """
        A password reset was requested for your DevForge AI account.

        Set a new password:

        %s

        This link can be used once and expires in 1 hour.
        If you did not request this, no action is needed — your password is unchanged.
        """.formatted(links.passwordResetLink(token)));
  }

  @Override
  public void sendWelcomeEmail(String email) {
    send(
        email,
        "Welcome to DevForge AI",
        """
        Your DevForge AI account is ready.

        %s
        """.formatted(links.getBaseUrl()));
  }

  private void send(String to, String subject, String body) {
    var message = new SimpleMailMessage();
    message.setFrom(links.getFromAddress());
    message.setTo(to);
    message.setSubject(subject);
    message.setText(body);

    try {
      mailSender.send(message);
      // The address is logged, never the token: these logs are widely readable and the
      // token in a verification link is a single-use credential.
      log.info("Sent '{}' to {}", subject, to);
    } catch (MailException ex) {
      // Rethrowing would roll back the signup transaction, destroying an account that was
      // otherwise created correctly because a mail relay was briefly unavailable. The user
      // can request another verification mail; they cannot recover a rolled-back signup.
      log.error("Failed to send '{}' to {}. The account is unaffected and the mail can be "
          + "re-requested.", subject, to, ex);
    }
  }
}
