package com.devforge.ai.authservice.service;

import com.devforge.ai.common.exception.ResourceConflictException;
import com.devforge.ai.authservice.entity.EmailVerificationTokenEntity;
import com.devforge.ai.authservice.entity.PasswordResetTokenEntity;
import com.devforge.ai.authservice.entity.RefreshTokenEntity;
import com.devforge.ai.authservice.entity.RoleEntity;
import com.devforge.ai.authservice.entity.UserEntity;
import com.devforge.ai.authservice.model.AccountStatus;
import com.devforge.ai.authservice.model.OAuthProvider;
import com.devforge.ai.authservice.model.RoleName;
import com.devforge.ai.authservice.repository.EmailVerificationTokenRepository;
import com.devforge.ai.authservice.repository.PasswordResetTokenRepository;
import com.devforge.ai.authservice.repository.RefreshTokenRepository;
import com.devforge.ai.authservice.repository.RoleRepository;
import com.devforge.ai.authservice.repository.UserRepository;
import com.devforge.ai.authservice.dto.TokenPair;
import com.devforge.ai.authservice.security.JwtTokenProvider;
import com.devforge.ai.authservice.security.UserPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final RefreshTokenRepository refreshTokenRepository;
  private final EmailVerificationTokenRepository emailVerificationTokenRepository;
  private final PasswordResetTokenRepository passwordResetTokenRepository;
  private final EmailService emailService;
  private final PasswordEncoder passwordEncoder;
  private final JwtTokenProvider jwtTokenProvider;
  private final AuditService auditService;
  private final SessionRevocationService sessionRevocationService;

  @Transactional
  public UserEntity registerUser(String firstName, String lastName, String username, String email, String password, String organization) {
    var existingEmail = userRepository.findByEmailIgnoreCase(email);
    if (existingEmail.isPresent()) {
      throw new ResourceConflictException("Email is already registered");
    }
    var existingUsername = userRepository.findByUsernameIgnoreCase(username);
    if (existingUsername.isPresent()) {
      throw new ResourceConflictException("Username is already taken");
    }

    var userRole = roleRepository.findByName(RoleName.DEVELOPER)
        .orElseThrow(() -> new IllegalStateException("Default role not configured"));

    // The id is deliberately NOT assigned here. UserEntity inherits @UuidGenerator from
    // BaseEntity, and Hibernate treats an entity that has a *generated* id strategy but an
    // already-populated id as detached. save() then routes to merge() instead of persist(),
    // @PrePersist never runs, and the @Version column stays null — which fails with
    // "Detached entity with generated id ... has an uninitialized version value".
    // Letting the generator assign the id keeps the instance transient so persist() runs.
    // (The token entities below use a plain assigned @Id with no generator, so they must
    // keep setting ids explicitly.)
    var user = UserEntity.builder()
        .firstName(firstName)
        .lastName(lastName)
        .username(username)
        .email(email)
        .organization(organization)
        .status(AccountStatus.PENDING_VERIFICATION)
        .oauthProvider(OAuthProvider.LOCAL)
        .passwordHash(passwordEncoder.encode(password))
        .emailVerified(false)
        .darkMode(true)
        .roles(Set.of(userRole))
        .build();

    userRepository.save(user);
    sendVerificationEmail(user);
    return user;
  }

  @Transactional
  public void sendVerificationEmail(UserEntity user) {
    var token = UUID.randomUUID().toString();
    var entity = EmailVerificationTokenEntity.builder()
        .id(UUID.randomUUID())
        .token(token)
        .user(user)
        .expiresAt(Instant.now().plusSeconds(86400))
        .build();
    emailVerificationTokenRepository.save(entity);
    emailService.sendEmailVerification(user.getEmail(), token);
  }

  @Transactional
  public void verifyEmail(String token) {
    var verification = emailVerificationTokenRepository.findByToken(token)
        .orElseThrow(() -> new IllegalArgumentException("Verification token is invalid or expired"));
    if (verification.getExpiresAt().isBefore(Instant.now())) {
      throw new IllegalArgumentException("Verification token is expired");
    }
    var user = verification.getUser();
    user.setEmailVerified(true);
    user.setStatus(AccountStatus.ACTIVE);
    userRepository.save(user);
    emailVerificationTokenRepository.delete(verification);
  }

  /**
   * Starts a password reset.
   *
   * <p>Deliberately silent when the address is unknown. Throwing (or otherwise varying the
   * response) would turn this endpoint into an account-enumeration oracle: an attacker could
   * submit addresses and learn which ones are registered. The controller returns the same
   * "password reset email sent" response either way.
   */
  @Transactional
  public void forgotPassword(String email) {
    var maybeUser = userRepository.findByEmailIgnoreCase(email);
    if (maybeUser.isEmpty()) {
      log.info("Password reset requested for an address with no account; responding as if sent.");
      return;
    }
    var user = maybeUser.get();
    var token = UUID.randomUUID().toString();
    var resetEntity = PasswordResetTokenEntity.builder()
        .id(UUID.randomUUID())
        .token(token)
        .user(user)
        .expiresAt(Instant.now().plusSeconds(3600))
        .build();
    passwordResetTokenRepository.save(resetEntity);
    emailService.sendPasswordReset(user.getEmail(), token);
  }

  @Transactional
  public void resetPassword(String token, String newPassword) {
    var reset = passwordResetTokenRepository.findByToken(token)
        .orElseThrow(() -> new IllegalArgumentException("Password reset token is invalid or expired"));
    if (reset.getExpiresAt().isBefore(Instant.now())) {
      throw new IllegalArgumentException("Password reset token is expired");
    }
    var user = reset.getUser();
    user.setPasswordHash(passwordEncoder.encode(newPassword));
    userRepository.save(user);
    passwordResetTokenRepository.delete(reset);
  }

  @Transactional
  public void changePassword(UUID userId, String currentPassword, String newPassword) {
    var user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
    if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
      throw new IllegalArgumentException("Current password is incorrect");
    }
    user.setPasswordHash(passwordEncoder.encode(newPassword));
    userRepository.save(user);
  }

  @Transactional
  public void deactivateAccount(UUID userId) {
    var user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
    user.setStatus(AccountStatus.DISABLED);
    userRepository.save(user);
  }

  @Transactional
  public String createAccessToken(org.springframework.security.core.Authentication authentication) {
    return jwtTokenProvider.createAccessToken(authentication);
  }

  @Transactional
  public String createRefreshToken(UserEntity user) {
    return jwtTokenProvider.createRefreshToken(user);
  }

  /**
   * Issues a refresh token for a user and persists it, replacing any existing one.
   *
   * <p>Takes a user id rather than a {@code UserEntity} on purpose. Callers used to pass
   * {@code UserPrincipal.toEntity()}, which fabricates a partially-populated instance carrying
   * an id but no {@code @Version}. Hibernate classifies that as a detached entity with a
   * generated id and refuses to associate it, so every login failed with
   * "uninitialized version value". Loading the managed entity here keeps persistence correct.
   */
  @Transactional
  public String issueAndStoreRefreshToken(UUID userId) {
    return issueAndStoreRefreshToken(userId, null, null, null);
  }

  /**
   * Issues a refresh token for one device and stores it as its own session.
   *
   * <p>Each device gets its own row. The previous behaviour deleted every token for the user on
   * each login, so signing in on a phone silently signed the same user out on their laptop, and
   * there was no way to see or revoke an individual session. Sessions are now independent, which
   * is what makes {@code GET /sessions} and per-session revocation meaningful.
   */
  @Transactional
  public String issueAndStoreRefreshToken(
      UUID userId, String deviceLabel, String userAgent, String ipAddress) {
    var user = userRepository.findById(userId)
        .orElseThrow(() -> new IllegalArgumentException("User not found"));
    var token = jwtTokenProvider.createRefreshToken(user);

    var refreshToken = RefreshTokenEntity.builder()
        .id(UUID.randomUUID())
        .token(token)
        .user(user)
        .expiresAt(Instant.now().plus(jwtTokenProvider.getRefreshTokenTtl()))
        .revoked(false)
        .deviceLabel(deviceLabel)
        .userAgent(userAgent)
        .ipAddress(ipAddress)
        .lastUsedAt(Instant.now())
        .build();
    refreshTokenRepository.save(refreshToken);
    return token;
  }

  @Transactional
  public void storeRefreshToken(UserEntity user, String token) {
    refreshTokenRepository.deleteByUser(user);
    var refreshToken = RefreshTokenEntity.builder()
        .id(UUID.randomUUID())
        .token(token)
        .user(user)
        .expiresAt(Instant.now().plus(jwtTokenProvider.getRefreshTokenTtl()))
        .revoked(false)
        .build();
    refreshTokenRepository.save(refreshToken);
  }

  /**
   * Resolves a login identifier, which may be either a username or an email address.
   *
   * <p>Used to attribute a failed login to an account for throttling and auditing. The caller
   * must not vary its response on whether this returns a user.
   */
  @Transactional(readOnly = true)
  public Optional<UserEntity> findByUsernameOrEmail(String usernameOrEmail) {
    if (usernameOrEmail == null || usernameOrEmail.isBlank()) {
      return Optional.empty();
    }
    var byUsername = userRepository.findByUsernameIgnoreCase(usernameOrEmail);
    return byUsername.isPresent() ? byUsername : userRepository.findByEmailIgnoreCase(usernameOrEmail);
  }

  @Transactional(readOnly = true)
  public Optional<UserEntity> findById(UUID userId) {
    return userRepository.findById(userId);
  }

  public String createMfaChallengeToken(UserEntity user) {
    return jwtTokenProvider.createMfaChallengeToken(user);
  }

  /**
   * Resolves the user behind an MFA challenge token.
   *
   * @return null when the token is invalid, expired, or not of the MFA challenge type. Requiring
   *     the type means an access or refresh token cannot be substituted here to skip the password
   *     step entirely.
   */
  public UUID userIdFromMfaChallenge(String challengeToken) {
    if (!jwtTokenProvider.validateToken(challengeToken, JwtTokenProvider.TOKEN_TYPE_MFA_CHALLENGE)) {
      return null;
    }
    try {
      return jwtTokenProvider.getUserIdFromToken(challengeToken);
    } catch (RuntimeException ex) {
      return null;
    }
  }

  // Cookie helpers only touch the HTTP response, so they deliberately carry no transaction.
  public void addRefreshCookie(HttpServletResponse response, String token) {
    jwtTokenProvider.addRefreshTokenCookie(response, token);
  }

  public void clearRefreshCookie(HttpServletResponse response) {
    jwtTokenProvider.clearRefreshTokenCookie(response);
  }

  @Transactional
  public void revokeRefreshToken(UUID userId) {
    var user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
    refreshTokenRepository.deleteByUser(user);
  }

  @Transactional
  public String extractRefreshTokenFromRequest(jakarta.servlet.http.HttpServletRequest request) {
    var cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (var cookie : cookies) {
      if (cookie.getName().equals(jwtTokenProvider.getJwtConfig().getCookieName())) {
        return cookie.getValue();
      }
    }
    return null;
  }

  @Transactional
  public boolean validateRefreshToken(String token) {
    // Must assert the REFRESH type explicitly: the no-arg validateToken() requires an
    // access token, so using it here would reject every legitimate refresh.
    if (!jwtTokenProvider.validateToken(token, JwtTokenProvider.TOKEN_TYPE_REFRESH)) {
      return false;
    }
    return refreshTokenRepository.findByToken(token)
        .map(refreshToken -> !refreshToken.isRevoked() && refreshToken.getExpiresAt().isAfter(Instant.now()))
        .orElse(false);
  }

  /**
   * Exchanges a refresh token for a new access token <em>and a new refresh token</em>.
   *
   * <p>Rotation matters: previously the same refresh token stayed valid for its full 14-day
   * lifetime, so a single captured token gave an attacker two weeks of access with no way to
   * detect or end it. Each exchange now invalidates the presented token.
   *
   * <p>Presenting a token that is cryptographically valid but no longer in the store means it was
   * already exchanged — the hallmark of a stolen token being replayed, since the legitimate client
   * would have moved on to its replacement. That cannot distinguish victim from thief, so the
   * safe response is to revoke every session for the account and force a fresh login.
   */
  @Transactional
  public TokenPair rotateRefreshToken(String presentedToken) {
    if (!jwtTokenProvider.validateToken(presentedToken, JwtTokenProvider.TOKEN_TYPE_REFRESH)) {
      throw new IllegalArgumentException("Refresh token invalid");
    }

    var userId = jwtTokenProvider.getUserIdFromToken(presentedToken);
    var stored = refreshTokenRepository.findByToken(presentedToken);

    if (stored.isEmpty()) {
      // Revoked in its own transaction: this method throws immediately afterwards, and a
      // revocation sharing that transaction would be rolled back by the throw.
      if (sessionRevocationService.revokeAllSessions(userId)) {
        log.warn("Refresh token reuse detected for user {}; revoked all sessions.", userId);
        userRepository.findById(userId).ifPresent(user ->
            auditService.record(user, AuditService.ACTION_TOKEN_REUSE_DETECTED, null,
                "A refresh token was replayed after rotation. All sessions revoked."));
      }
      throw new IllegalArgumentException("Refresh token invalid");
    }

    var entity = stored.get();
    if (entity.isRevoked() || entity.getExpiresAt().isBefore(Instant.now())) {
      throw new IllegalArgumentException("Refresh token invalid");
    }

    var user = entity.getUser();
    // A user disabled, locked or deleted since the token was issued must not be able to
    // extend their session by refreshing.
    if (user.getStatus() != AccountStatus.ACTIVE) {
      refreshTokenRepository.deleteByUser(user);
      throw new IllegalArgumentException("Refresh token invalid");
    }

    // Carry the device identity across the rotation, or every refresh would orphan the
    // session's metadata and the session list would fill with anonymous entries.
    var deviceLabel = entity.getDeviceLabel();
    var userAgent = entity.getUserAgent();
    var ipAddress = entity.getIpAddress();

    // Consume the presented token before issuing its replacement.
    refreshTokenRepository.delete(entity);
    refreshTokenRepository.flush();

    var principal = UserPrincipal.fromEntity(user);
    var authentication = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
        principal, null, principal.getAuthorities());
    var accessToken = jwtTokenProvider.createAccessToken(authentication);
    var newRefreshToken =
        issueAndStoreRefreshToken(user.getId(), deviceLabel, userAgent, ipAddress);

    auditService.record(user, AuditService.ACTION_TOKEN_REFRESHED, null, "Refresh token rotated.");
    return new TokenPair(accessToken, newRefreshToken);
  }

  @Transactional
  public String refreshAccessToken(String refreshToken) {
    var refreshEntity = refreshTokenRepository.findByToken(refreshToken)
        .orElseThrow(() -> new IllegalArgumentException("Refresh token not found"));
    if (refreshEntity.isRevoked() || refreshEntity.getExpiresAt().isBefore(Instant.now())) {
      throw new IllegalArgumentException("Refresh token invalid");
    }
    var user = refreshEntity.getUser();
    var principal = UserPrincipal.fromEntity(user);
    var authentication = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    return jwtTokenProvider.createAccessToken(authentication);
  }

  @Transactional
  public void deleteAccount(UUID userId) {
    var user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
    user.setStatus(AccountStatus.DELETED);
    user.setDeletedAt(Instant.now());
    userRepository.save(user);
  }
}
