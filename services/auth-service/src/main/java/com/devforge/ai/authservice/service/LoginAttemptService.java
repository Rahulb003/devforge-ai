package com.devforge.ai.authservice.service;

import com.devforge.ai.authservice.repository.LoginHistoryRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Brute-force protection for password login.
 *
 * <p>The service previously had none: an attacker could try passwords against a known account
 * as fast as the network allowed, bounded only by BCrypt's cost factor.
 *
 * <p>Lockout is <em>time-windowed rather than sticky</em>. Crossing the threshold blocks further
 * attempts until the window rolls forward; it does not set {@code AccountStatus.LOCKED}. A sticky
 * lock would let anyone disable an arbitrary account permanently just by submitting bad passwords,
 * turning a brute-force defence into a denial-of-service tool. The window still costs an attacker
 * a hard cap on guesses per unit time, which is the property that matters.
 *
 * <p>This counts failures for a <em>known</em> user id. Attempts against usernames that do not
 * exist are not counted here, because recording them per-username would itself leak which
 * accounts exist; those need IP-level limiting at the gateway.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

  private final LoginHistoryRepository loginHistoryRepository;

  /** Failed attempts allowed inside the window before further attempts are refused. */
  @Value("${security.login.max-failed-attempts:5}")
  private int maxFailedAttempts;

  /** Rolling window over which failures are counted. */
  @Value("${security.login.failure-window:15m}")
  private Duration failureWindow;

  /**
   * Returns {@code true} when this account has exceeded the failure threshold inside the window
   * and must be refused without checking the password.
   */
  @Transactional(readOnly = true)
  public boolean isBlocked(UUID userId) {
    var failures = recentFailures(userId);
    if (failures >= maxFailedAttempts) {
      log.warn("Login blocked for user {}: {} failures within {}", userId, failures, failureWindow);
      return true;
    }
    return false;
  }

  @Transactional(readOnly = true)
  public long recentFailures(UUID userId) {
    var since = Instant.now().minus(failureWindow);
    return loginHistoryRepository.countByUserIdAndStatusAndCreatedAtAfter(
        userId, AuditService.STATUS_FAILURE, since);
  }

  /** Remaining attempts before the account is refused, floored at zero. */
  @Transactional(readOnly = true)
  public long remainingAttempts(UUID userId) {
    return Math.max(0, maxFailedAttempts - recentFailures(userId));
  }

  public Duration getFailureWindow() {
    return failureWindow;
  }

  public int getMaxFailedAttempts() {
    return maxFailedAttempts;
  }
}
