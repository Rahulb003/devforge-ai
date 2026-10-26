package com.devforge.ai.authservice.service;

import com.devforge.ai.authservice.repository.AuditLogRepository;
import com.devforge.ai.authservice.repository.LoginHistoryRepository;
import com.devforge.ai.authservice.repository.PersonalAccessTokenRepository;
import com.devforge.ai.authservice.repository.RefreshTokenRepository;
import com.devforge.ai.authservice.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything auth-service holds about the caller, as one document they can keep (data portability).
 *
 * <p>Built field by field rather than by serialising the entities, so a column added later is not
 * exported - or leaked - until someone decides it should be. Credentials are never included in any
 * form: no password hash, MFA secret, recovery code or token hash, and no refresh token value.
 *
 * <p>Only this service's data. Organization memberships, tasks, repositories and the rest live in
 * other services and are not part of this export.
 */
@Service
@RequiredArgsConstructor
public class AccountExportService {

  /** Generous for one person's history, and a ceiling on what one request can make the database do. */
  static final int MAX_ROWS = 10_000;

  private final UserRepository users;
  private final RefreshTokenRepository refreshTokens;
  private final PersonalAccessTokenRepository personalAccessTokens;
  private final LoginHistoryRepository loginHistory;
  private final AuditLogRepository auditLogs;

  public record Export(Instant exportedAt, String scope, Profile profile, List<Session> sessions,
      List<AccessToken> personalAccessTokens, List<LinkedProvider> linkedProviders,
      List<SignIn> signInHistory, List<AuditEntry> auditTrail) {}

  public record Profile(UUID id, String username, String email, boolean emailVerified, String firstName,
      String lastName, String phone, String organization, String timezone, String language,
      String status, boolean mfaEnabled, Instant createdAt, Instant lastLogin) {}

  public record Session(String device, String userAgent, String ipAddress, Instant createdAt,
      Instant lastUsedAt, Instant expiresAt, boolean revoked) {}

  public record AccessToken(String name, String prefix, Instant createdAt, Instant expiresAt,
      Instant lastUsedAt, Instant revokedAt) {}

  public record LinkedProvider(String provider, String providerAccountId, Instant linkedAt) {}

  public record SignIn(Instant at, String type, String status, String failureReason, String ipAddress,
      String userAgent) {}

  public record AuditEntry(Instant at, String action, String ipAddress, String details) {}

  @Transactional(readOnly = true)
  public Export export(UUID userId) {
    var user = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
    var rows = PageRequest.of(0, MAX_ROWS);
    return new Export(
        Instant.now(),
        "The account data held by DevForge's authentication service. Organization, project, task and"
            + " repository data is held by other services and is not included.",
        new Profile(user.getId(), user.getUsername(), user.getEmail(), user.isEmailVerified(),
            user.getFirstName(), user.getLastName(), user.getPhone(), user.getOrganization(),
            user.getTimezone(), user.getLanguage(), user.getStatus().name(), user.isMfaEnabled(),
            user.getCreatedAt(), user.getLastLogin()),
        refreshTokens.findByUser(user).stream()
            .map(t -> new Session(t.getDeviceLabel(), t.getUserAgent(), t.getIpAddress(), t.getCreatedAt(),
                t.getLastUsedAt(), t.getExpiresAt(), t.isRevoked()))
            .toList(),
        personalAccessTokens.findByUserIdOrderByCreatedAtDesc(userId).stream()
            .map(t -> new AccessToken(t.getName(), t.getTokenPrefix(), t.getCreatedAt(), t.getExpiresAt(),
                t.getLastUsedAt(), t.getRevokedAt()))
            .toList(),
        user.getOauthAccounts().stream()
            .map(a -> new LinkedProvider(a.getProvider().name(), a.getProviderId(), a.getCreatedAt()))
            .toList(),
        loginHistory.findByUserIdOrderByCreatedAtDesc(userId, rows).stream()
            .map(h -> new SignIn(h.getCreatedAt(), h.getLoginType(), h.getStatus(), h.getFailureReason(),
                h.getIpAddress(), h.getUserAgent()))
            .toList(),
        auditLogs.findByUserIdOrderByCreatedAtDesc(userId, rows).stream()
            .map(a -> new AuditEntry(a.getCreatedAt(), a.getAction(), a.getIpAddress(), a.getDetails()))
            .toList());
  }
}
