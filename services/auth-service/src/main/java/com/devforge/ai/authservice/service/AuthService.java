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
import com.devforge.ai.authservice.security.JwtTokenProvider;
import com.devforge.ai.authservice.security.UserPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.transaction.Transactional;
import java.time.Instant;
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
    var user = userRepository.findById(userId)
        .orElseThrow(() -> new IllegalArgumentException("User not found"));
    var token = jwtTokenProvider.createRefreshToken(user);

    // One active refresh token per user: replace rather than accumulate.
    refreshTokenRepository.deleteByUser(user);
    refreshTokenRepository.flush();

    var refreshToken = RefreshTokenEntity.builder()
        .id(UUID.randomUUID())
        .token(token)
        .user(user)
        .expiresAt(Instant.now().plus(jwtTokenProvider.getRefreshTokenTtl()))
        .revoked(false)
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
