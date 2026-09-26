package com.devforge.ai.authservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Development email provider: writes messages to the log instead of sending them.
 *
 * <p>Active only under the {@code standalone} profile, which exists so the platform can be run and
 * inspected without Postgres, Kafka, Redis or an SMTP relay. {@link EmailServiceImpl} is excluded
 * from that profile, so exactly one implementation is ever present.
 *
 * <p>This is a development provider in the sense of section 125, not a mock pretending to be
 * production behaviour: it is explicitly named, confined to one profile, and swapped out by
 * declaring a real {@code EmailService}. It logs the verification and reset links precisely because
 * an operator needs them to complete a flow locally — which also means <b>this profile must never
 * be enabled anywhere real</b>, since those links are single-use credentials.
 */
@Slf4j
@Service
@Profile("standalone")
public class LoggingEmailService implements EmailService {

  @Override
  public void sendEmailVerification(String email, String token) {
    log.warn("""

        ==================== DEV EMAIL (not sent) ====================
        To:      {}
        Subject: Verify your DevForge AI email
        Verify:  POST /api/v1/auth/verify-email?token={}
        ==============================================================
        """, email, token);
  }

  @Override
  public void sendPasswordReset(String email, String token) {
    log.warn("""

        ==================== DEV EMAIL (not sent) ====================
        To:      {}
        Subject: Reset your DevForge AI password
        Token:   {}
        ==============================================================
        """, email, token);
  }

  @Override
  public void sendWelcomeEmail(String email) {
    log.info("DEV EMAIL (not sent): welcome message for {}", email);
  }
}
