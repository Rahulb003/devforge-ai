package com.devforge.ai.authservice.service;

import com.devforge.ai.authservice.client.OrganizationMembershipClient;
import com.devforge.ai.authservice.entity.UserEntity;
import com.devforge.ai.authservice.events.IdentityEventPublisher;
import com.devforge.ai.authservice.model.AccountStatus;
import com.devforge.ai.authservice.repository.EmailVerificationTokenRepository;
import com.devforge.ai.authservice.repository.LoginHistoryRepository;
import com.devforge.ai.authservice.repository.MfaBackupCodeRepository;
import com.devforge.ai.authservice.repository.OAuthAccountRepository;
import com.devforge.ai.authservice.repository.PasswordResetTokenRepository;
import com.devforge.ai.authservice.repository.PersonalAccessTokenRepository;
import com.devforge.ai.authservice.repository.RefreshTokenRepository;
import com.devforge.ai.authservice.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes the caller's own account: the right to erasure.
 *
 * <p>In three steps, each in its own transaction, because the middle one is another service:
 * confirm it is really the owner asking, leave every organization at project-service, then erase.
 * Confirmation commits even when it fails, so a wrong password counts toward the lockout as at
 * sign-in. If the erase failed after the organizations were left, the user is simply out of their
 * organizations and can try again - nothing is left half-erased.
 *
 * <p>The account row stays, emptied: the id is what every other service's records point at, and a
 * reused id would hand a stranger the deleted user's history. Credentials, sessions, tokens,
 * recovery codes, linked sign-in providers and sign-in history are deleted. The audit trail is
 * kept as written: its hash chain is what makes it trustworthy, and rewriting entries would break
 * it - see docs/SECURITY.md.
 */
@Service
@RequiredArgsConstructor
public class AccountDeletionService {

  public static final String ACTION_ACCOUNT_DELETED = "ACCOUNT_DELETED";

  private final UserRepository users;
  private final PasswordEncoder passwordEncoder;
  private final LoginAttemptService loginAttempts;
  private final AuditService audit;
  private final MfaService mfa;
  private final IdentityEventPublisher events;
  private final OrganizationMembershipClient memberships;
  private final RefreshTokenRepository refreshTokens;
  private final PersonalAccessTokenRepository personalAccessTokens;
  private final MfaBackupCodeRepository backupCodes;
  private final EmailVerificationTokenRepository verificationTokens;
  private final PasswordResetTokenRepository resetTokens;
  private final LoginHistoryRepository loginHistory;
  private final OAuthAccountRepository oauthAccounts;
  private final PlatformTransactionManager transactionManager;

  /**
   * What the owner must give: their username typed out, so a stray click cannot do it; the
   * password, so a stolen session cannot; and a second factor where one is enrolled.
   */
  public record Confirmation(String username, String password, String mfaCode) {}

  public void delete(UUID userId, Confirmation confirmation, String bearerToken, HttpServletRequest request) {
    var ip = audit.clientIp(request);
    var failure = new TransactionTemplate(transactionManager).execute(tx -> confirm(userId, confirmation, request));
    if (failure != null) {
      throw new IllegalArgumentException(failure);
    }
    memberships.leaveAllOrganizations(bearerToken);
    new TransactionTemplate(transactionManager).executeWithoutResult(tx -> erase(userId, ip));
  }

  /** The reason confirmation failed, or null. Returned, not thrown, so the failure is recorded. */
  private String confirm(UUID userId, Confirmation confirmation, HttpServletRequest request) {
    var user = load(userId);
    if (loginAttempts.isBlocked(userId)) {
      return "Too many failed attempts. Try again later.";
    }
    if (confirmation == null || confirmation.username() == null
        || !confirmation.username().trim().equalsIgnoreCase(user.getUsername())) {
      return "Type your username to confirm";
    }
    // An account created through a sign-in provider has no password to ask for.
    if (user.getPasswordHash() != null
        && (confirmation.password() == null || !passwordEncoder.matches(confirmation.password(), user.getPasswordHash()))) {
      audit.recordLoginAttempt(user, "ACCOUNT_DELETION", AuditService.STATUS_FAILURE, "Wrong password", request);
      return "Password is incorrect";
    }
    if (user.isMfaEnabled() && !mfa.verifyCode(user, confirmation.mfaCode())) {
      audit.recordLoginAttempt(user, "ACCOUNT_DELETION", AuditService.STATUS_FAILURE, "Wrong second factor", request);
      return "A valid authentication code is required";
    }
    return null;
  }

  private void erase(UUID userId, String ip) {
    var user = load(userId);
    refreshTokens.deleteByUser(user);
    personalAccessTokens.deleteByUserId(userId);
    backupCodes.deleteByUser(user);
    verificationTokens.deleteByUser(user);
    resetTokens.deleteByUser(user);
    loginHistory.deleteByUser(user);
    oauthAccounts.deleteByUser(user);

    // Derived from the id, so unique without a lookup, and recognisable wherever it is shown.
    var tag = userId.toString().replace("-", "");
    user.setUsername("deleted-" + tag);
    user.setEmail("deleted-" + userId + "@deleted.invalid");
    user.setFirstName("Deleted");
    user.setLastName("user");
    user.setPhone(null);
    user.setAvatarUrl(null);
    user.setOrganization(null);
    user.setPasswordHash(null);
    user.setEmailVerified(false);
    user.setMfaEnabled(false);
    user.setMfaSecret(null);
    user.setMfaEnrolledAt(null);
    user.setLastLogin(null);
    user.setStatus(AccountStatus.DELETED);
    user.setDeletedAt(Instant.now());

    audit.record(user, ACTION_ACCOUNT_DELETED, ip, "Account deleted by its owner; personal data erased.");
    events.userDeleted(user);
  }

  private UserEntity load(UUID userId) {
    return users.findById(userId)
        .filter(u -> u.getStatus() != AccountStatus.DELETED)
        .orElseThrow(() -> new IllegalArgumentException("User not found"));
  }
}
