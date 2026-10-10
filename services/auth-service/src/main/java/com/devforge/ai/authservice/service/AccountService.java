package com.devforge.ai.authservice.service;

import com.devforge.ai.authservice.entity.UserEntity;
import com.devforge.ai.authservice.events.IdentityEventPublisher;
import com.devforge.ai.authservice.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The signed-in user changing their own account: profile fields and password. */
@Service
@RequiredArgsConstructor
public class AccountService {

  /** Same rule as signup, so changing a password cannot weaken it below what an account needs. */
  static final Pattern PASSWORD_RULE =
      Pattern.compile("(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[^a-zA-Z0-9]).{12,}");
  private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2}(-[A-Z]{2})?");

  private final UserRepository users;
  private final PasswordEncoder passwordEncoder;
  private final AuditService auditService;
  private final LoginAttemptService loginAttemptService;
  private final IdentityEventPublisher events;

  public record ProfileChanges(String firstName, String lastName, String timezone, String language) {}

  /** Only the fields given change; each is validated as it would be at signup. */
  @Transactional
  public UserEntity updateProfile(UUID userId, ProfileChanges changes) {
    var user = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
    if (changes.firstName() != null) {
      user.setFirstName(requireName(changes.firstName(), "First name"));
    }
    if (changes.lastName() != null) {
      user.setLastName(requireName(changes.lastName(), "Last name"));
    }
    if (changes.timezone() != null) {
      try {
        user.setTimezone(ZoneId.of(changes.timezone()).getId());
      } catch (DateTimeException ex) {
        throw new IllegalArgumentException("Unknown time zone: use a region such as Europe/Berlin");
      }
    }
    if (changes.language() != null) {
      if (!LANGUAGE.matcher(changes.language()).matches()) {
        throw new IllegalArgumentException("Language must be a code such as en or en-GB");
      }
      user.setLanguage(changes.language());
    }
    auditService.record(user, "PROFILE_UPDATED", null, "Profile updated by the user.");
    return user;
  }

  /**
   * Changes the password, given the current one.
   *
   * <p>Asking for the current password is what stops someone at an unlocked browser, or holding a
   * stolen session, from locking the owner out. A wrong one counts toward the same lockout as a
   * failed sign-in, so this cannot be used to guess it at leisure.
   */
  @Transactional(noRollbackFor = IllegalArgumentException.class)
  public void changePassword(
      UUID userId, String currentPassword, String newPassword, HttpServletRequest request) {
    var user = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
    if (loginAttemptService.isBlocked(userId)) {
      throw new IllegalArgumentException("Too many failed attempts. Try again later.");
    }
    if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
      auditService.recordLoginAttempt(user, "PASSWORD_CHANGE", AuditService.STATUS_FAILURE,
          "Wrong current password", request);
      throw new IllegalArgumentException("Current password is incorrect");
    }
    if (newPassword == null || !PASSWORD_RULE.matcher(newPassword).matches()) {
      throw new IllegalArgumentException(
          "Password must be at least 12 characters with uppercase, lowercase, a number and a symbol");
    }
    if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
      throw new IllegalArgumentException("The new password must differ from the current one");
    }
    user.setPasswordHash(passwordEncoder.encode(newPassword));
    auditService.record(user, "PASSWORD_CHANGED", auditService.clientIp(request),
        "Password changed by the user.");
    // The owner is told, through notification-service: the alert they need if it was not them.
    events.passwordReset(user);
  }

  private static String requireName(String value, String field) {
    if (value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(field + " cannot contain control characters");
    }
    var trimmed = value.trim();
    if (trimmed.isEmpty() || trimmed.length() > 100) {
      throw new IllegalArgumentException(field + " must be 1 to 100 characters");
    }
    return trimmed;
  }
}
