package com.devforge.ai.authservice.service;

import com.devforge.ai.authservice.entity.MfaBackupCodeEntity;
import com.devforge.ai.authservice.entity.UserEntity;
import com.devforge.ai.authservice.repository.MfaBackupCodeRepository;
import com.devforge.ai.authservice.repository.UserRepository;
import com.devforge.ai.authservice.security.TotpService;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TOTP enrolment, verification and recovery codes.
 *
 * <p>Enrolment is two-step on purpose. {@code beginEnrolment} generates a secret and returns it
 * once, but leaves MFA off; {@code confirmEnrolment} only switches it on after the user proves
 * their authenticator produces a valid code. Enabling MFA on the strength of a generated secret
 * alone would lock users out whenever the QR code was mis-scanned.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MfaService {

  private static final String BACKUP_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  private static final int BACKUP_CODE_LENGTH = 10;

  private final UserRepository userRepository;
  private final MfaBackupCodeRepository backupCodeRepository;
  private final TotpService totpService;
  private final PasswordEncoder passwordEncoder;
  private final AuditService auditService;

  @Value("${security.mfa.issuer:DevForge AI}")
  private String issuer;

  @Value("${security.mfa.backup-code-count:10}")
  private int backupCodeCount;

  /** What the client needs to display a QR code. Returned exactly once. */
  public record EnrolmentChallenge(String secret, String provisioningUri) {}

  /** Recovery codes, shown once at enrolment and never retrievable afterwards. */
  public record BackupCodes(List<String> codes) {}

  /**
   * Starts enrolment: generates and stores a secret, but leaves MFA disabled.
   *
   * <p>Re-enrolling while already enabled is refused. Silently replacing a working secret would
   * invalidate the user's existing authenticator without warning, and would let anyone holding a
   * live session quietly swap the second factor for one they control.
   */
  @Transactional
  public EnrolmentChallenge beginEnrolment(UUID userId) {
    var user = requireUser(userId);
    if (user.isMfaEnabled()) {
      throw new IllegalArgumentException(
          "MFA is already enabled. Disable it before enrolling a new device.");
    }

    var secret = totpService.generateSecret();
    user.setMfaSecret(secret);
    user.setMfaEnabled(false);
    userRepository.save(user);

    return new EnrolmentChallenge(
        secret, totpService.provisioningUri(secret, user.getEmail(), issuer));
  }

  /**
   * Completes enrolment once the user proves possession of the secret.
   *
   * @return freshly generated recovery codes, which are shown once and never again.
   */
  @Transactional
  public BackupCodes confirmEnrolment(UUID userId, String code) {
    var user = requireUser(userId);
    if (user.getMfaSecret() == null) {
      throw new IllegalArgumentException("MFA enrolment has not been started");
    }
    if (user.isMfaEnabled()) {
      throw new IllegalArgumentException("MFA is already enabled");
    }
    if (!totpService.verify(user.getMfaSecret(), code)) {
      throw new IllegalArgumentException("Invalid verification code");
    }

    user.setMfaEnabled(true);
    user.setMfaEnrolledAt(Instant.now());
    userRepository.save(user);

    var codes = regenerateBackupCodes(user);
    auditService.record(user, "MFA_ENABLED", null, "TOTP enrolment confirmed.");
    log.info("MFA enabled for user {}", userId);
    return new BackupCodes(codes);
  }

  /**
   * Turns MFA off.
   *
   * <p>Requires a current TOTP code or an unused recovery code. Allowing a session alone to
   * disable MFA would mean a stolen access token could strip the second factor, which is the
   * thing MFA exists to prevent.
   */
  @Transactional
  public void disable(UUID userId, String code) {
    var user = requireUser(userId);
    if (!user.isMfaEnabled()) {
      throw new IllegalArgumentException("MFA is not enabled");
    }
    if (!verifyCode(user, code)) {
      throw new IllegalArgumentException("Invalid verification code");
    }

    user.setMfaEnabled(false);
    user.setMfaSecret(null);
    user.setMfaEnrolledAt(null);
    userRepository.save(user);
    backupCodeRepository.deleteByUser(user);

    auditService.record(user, "MFA_DISABLED", null, "TOTP disabled by user.");
    log.info("MFA disabled for user {}", userId);
  }

  /**
   * Verifies a second factor: a TOTP code, or a single-use recovery code.
   *
   * <p>A recovery code is consumed on success, so it cannot be replayed.
   */
  @Transactional
  public boolean verifyCode(UserEntity user, String code) {
    if (code == null || code.isBlank()) {
      return false;
    }
    if (user.getMfaSecret() != null && totpService.verify(user.getMfaSecret(), code)) {
      return true;
    }
    return consumeBackupCode(user, code);
  }

  private boolean consumeBackupCode(UserEntity user, String code) {
    var normalised = code.trim().toUpperCase(Locale.ROOT).replace("-", "");
    for (var candidate : backupCodeRepository.findByUserIdAndUsedAtIsNull(user.getId())) {
      if (passwordEncoder.matches(normalised, candidate.getCodeHash())) {
        candidate.setUsedAt(Instant.now());
        backupCodeRepository.save(candidate);
        auditService.record(user, "MFA_BACKUP_CODE_USED", null,
            "A single-use recovery code was consumed. %d remain."
                .formatted(backupCodeRepository.countByUserIdAndUsedAtIsNull(user.getId())));
        log.warn("Backup code consumed for user {}", user.getId());
        return true;
      }
    }
    return false;
  }

  /** Replaces all recovery codes, invalidating any previously issued ones. */
  @Transactional
  public BackupCodes regenerate(UUID userId, String code) {
    var user = requireUser(userId);
    if (!user.isMfaEnabled()) {
      throw new IllegalArgumentException("MFA is not enabled");
    }
    if (!verifyCode(user, code)) {
      throw new IllegalArgumentException("Invalid verification code");
    }
    return new BackupCodes(regenerateBackupCodes(user));
  }

  private List<String> regenerateBackupCodes(UserEntity user) {
    backupCodeRepository.deleteByUser(user);

    var random = new SecureRandom();
    var plain = new ArrayList<String>(backupCodeCount);
    for (int i = 0; i < backupCodeCount; i++) {
      var builder = new StringBuilder(BACKUP_CODE_LENGTH);
      for (int c = 0; c < BACKUP_CODE_LENGTH; c++) {
        builder.append(BACKUP_CODE_ALPHABET.charAt(random.nextInt(BACKUP_CODE_ALPHABET.length())));
      }
      var value = builder.toString();
      plain.add(value);
      // Hashed with the same encoder as passwords; only the hash is persisted.
      backupCodeRepository.save(MfaBackupCodeEntity.builder()
          .user(user)
          .codeHash(passwordEncoder.encode(value))
          .build());
    }
    return plain;
  }

  @Transactional(readOnly = true)
  public long remainingBackupCodes(UUID userId) {
    return backupCodeRepository.countByUserIdAndUsedAtIsNull(userId);
  }

  private UserEntity requireUser(UUID userId) {
    return userRepository.findById(userId)
        .orElseThrow(() -> new IllegalArgumentException("User not found"));
  }
}
