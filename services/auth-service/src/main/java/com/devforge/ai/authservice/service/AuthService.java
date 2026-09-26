package com.devforge.ai.authservice.service;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

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
      throw new IllegalArgumentException("Email is already registered");
    }
    var existingUsername = userRepository.findByUsernameIgnoreCase(username);
    if (existingUsername.isPresent()) {
      throw new IllegalArgumentException("Username is already taken");
    }

    var userRole = roleRepository.findByName(RoleName.DEVELOPER)
        .orElseThrow(() -> new IllegalStateException("Default role not configured"));

    var user = UserEntity.builder()
        .id(UUID.randomUUID())
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

  @Transactional
  public void forgotPassword(String email) {
    var user = userRepository.findByEmailIgnoreCase(email)
        .orElseThrow(() -> new IllegalArgumentException("User with supplied email does not exist"));
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
    if (!jwtTokenProvider.validateToken(token)) {
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
