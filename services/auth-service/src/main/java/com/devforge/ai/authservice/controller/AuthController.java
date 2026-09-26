package com.devforge.ai.authservice.controller;

import com.devforge.ai.authservice.dto.ApiResponseDto;
import com.devforge.ai.authservice.dto.ChangePasswordRequest;
import com.devforge.ai.authservice.dto.ForgotPasswordRequest;
import com.devforge.ai.authservice.dto.LoginRequest;
import com.devforge.ai.authservice.dto.PasswordResetRequest;
import com.devforge.ai.authservice.dto.SignupRequest;
import com.devforge.ai.authservice.dto.UserProfileResponse;
import com.devforge.ai.authservice.entity.UserEntity;
import com.devforge.ai.authservice.security.UserPrincipal;
import com.devforge.ai.authservice.service.AuthService;
import com.devforge.ai.authservice.service.AuditService;
import com.devforge.ai.authservice.service.CustomUserDetailsService;
import com.devforge.ai.authservice.service.LoginAttemptService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Validated
public class AuthController {

  private final AuthService authService;
  private final AuthenticationManager authenticationManager;
  private final CustomUserDetailsService userDetailsService;
  private final PasswordEncoder passwordEncoder;
  private final AuditService auditService;
  private final LoginAttemptService loginAttemptService;

  @PostMapping("/signup")
  public ResponseEntity<ApiResponseDto<UserProfileResponse>> signup(@Valid @RequestBody SignupRequest request) {
    var user = authService.registerUser(request.getFirstName(), request.getLastName(), request.getUsername(), request.getEmail(), request.getPassword(), request.getOrganization());
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponseDto.<UserProfileResponse>builder()
        .success(true)
        .data(toProfile(user))
        .message("Signup successful. Verification email sent.")
        .build());
  }

  /**
   * Password login.
   *
   * <p>Every failure path returns the same body and status. The response must not reveal whether
   * the username exists, whether the password was wrong, or whether the account is throttled —
   * each of those would be an enumeration signal. The distinction is recorded in the audit trail
   * instead, where only operators can see it.
   */
  @PostMapping("/login")
  public ResponseEntity<ApiResponseDto<String>> login(
      @Valid @RequestBody LoginRequest request,
      HttpServletRequest httpRequest,
      HttpServletResponse response) {

    // Resolved up-front so a failed attempt can be attributed to an account. Absent means the
    // identifier does not exist; that is handled identically below.
    var existingUser = authService.findByUsernameOrEmail(request.getUsernameOrEmail()).orElse(null);

    if (existingUser != null && loginAttemptService.isBlocked(existingUser.getId())) {
      auditService.recordLoginAttempt(existingUser, AuditService.LOGIN_TYPE_PASSWORD,
          AuditService.STATUS_FAILURE, "Blocked: too many recent failures", httpRequest);
      auditService.record(existingUser, AuditService.ACTION_LOGIN_BLOCKED,
          auditService.clientIp(httpRequest),
          "Login refused: more than %d failures within %s."
              .formatted(loginAttemptService.getMaxFailedAttempts(),
                  loginAttemptService.getFailureWindow()));
      return unauthorized();
    }

    try {
      Authentication authentication = authenticationManager.authenticate(
          new UsernamePasswordAuthenticationToken(request.getUsernameOrEmail(), request.getPassword()));
      var accessToken = authService.createAccessToken(authentication);
      var user = (UserPrincipal) authentication.getPrincipal();
      var refreshToken = authService.issueAndStoreRefreshToken(user.getId());
      authService.addRefreshCookie(response, refreshToken);

      authService.findById(user.getId()).ifPresent(entity -> {
        auditService.recordLoginAttempt(entity, AuditService.LOGIN_TYPE_PASSWORD,
            AuditService.STATUS_SUCCESS, null, httpRequest);
        auditService.record(entity, AuditService.ACTION_LOGIN_SUCCESS,
            auditService.clientIp(httpRequest), "Password login succeeded.");
      });

      return ResponseEntity.ok(ApiResponseDto.<String>builder()
          .success(true).data(accessToken).message("Login successful").build());
    } catch (AuthenticationException ex) {
      // Recorded against the account when we know it; this is what the throttle counts.
      auditService.recordLoginAttempt(existingUser, AuditService.LOGIN_TYPE_PASSWORD,
          AuditService.STATUS_FAILURE, ex.getClass().getSimpleName(), httpRequest);
      auditService.record(existingUser, AuditService.ACTION_LOGIN_FAILURE,
          auditService.clientIp(httpRequest), "Password login failed.");
      return unauthorized();
    }
  }

  private ResponseEntity<ApiResponseDto<String>> unauthorized() {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(ApiResponseDto.<String>builder().success(false).message("Invalid credentials").build());
  }

  /**
   * Revokes the caller's refresh token and clears the refresh cookie.
   *
   * <p>Uses {@code @AuthenticationPrincipal} rather than a java.security.Principal parameter. Spring
   * injects the {@code Authentication} itself into a {@code Principal} argument, and that is a
   * {@code UsernamePasswordAuthenticationToken}, never a {@link UserPrincipal} — so the previous
   * {@code instanceof UserPrincipal} check could never match and logout always returned 401.
   */
  @PostMapping("/logout")
  public ResponseEntity<ApiResponseDto<Void>> logout(
      HttpServletResponse response, @AuthenticationPrincipal UserPrincipal userPrincipal) {
    if (userPrincipal == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(ApiResponseDto.<Void>builder().success(false).message("Unauthorized").build());
    }
    authService.revokeRefreshToken(userPrincipal.getId());
    authService.clearRefreshCookie(response);
    authService.findById(userPrincipal.getId()).ifPresent(entity ->
        auditService.record(entity, AuditService.ACTION_LOGOUT, null, "Session revoked."));
    return ResponseEntity.ok(ApiResponseDto.<Void>builder().success(true).message("Logout successful").build());
  }

  /**
   * Exchanges the refresh cookie for a new access token, rotating the refresh token.
   *
   * <p>The response sets a replacement cookie: the presented token is consumed and will not work
   * again. Replaying it revokes every session for the account, so a stolen token cannot be used
   * alongside the legitimate client without being noticed.
   */
  @PostMapping("/refresh")
  public ResponseEntity<ApiResponseDto<String>> refresh(HttpServletRequest request, HttpServletResponse response) {
    var refreshToken = authService.extractRefreshTokenFromRequest(request);
    if (refreshToken == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(ApiResponseDto.<String>builder().success(false).message("Refresh token invalid").build());
    }

    final String newAccessToken;
    try {
      var tokens = authService.rotateRefreshToken(refreshToken);
      authService.addRefreshCookie(response, tokens.refreshToken());
      newAccessToken = tokens.accessToken();
    } catch (IllegalArgumentException ex) {
      // Clear the cookie so a client holding a dead token stops replaying it.
      authService.clearRefreshCookie(response);
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(ApiResponseDto.<String>builder().success(false).message("Refresh token invalid").build());
    }
    return ResponseEntity.ok(ApiResponseDto.<String>builder().success(true).data(newAccessToken).message("Token refreshed").build());
  }

  @PostMapping("/forgot-password")
  public ResponseEntity<ApiResponseDto<Void>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
    authService.forgotPassword(request.getEmail());
    return ResponseEntity.ok(ApiResponseDto.<Void>builder().success(true).message("Password reset email sent").build());
  }

  @PostMapping("/reset-password")
  public ResponseEntity<ApiResponseDto<Void>> resetPassword(@Valid @RequestBody PasswordResetRequest request) {
    authService.resetPassword(request.getToken(), request.getNewPassword());
    return ResponseEntity.ok(ApiResponseDto.<Void>builder().success(true).message("Password has been reset").build());
  }

  @PostMapping("/verify-email")
  public ResponseEntity<ApiResponseDto<Void>> verifyEmail(@RequestParam("token") String token) {
    authService.verifyEmail(token);
    return ResponseEntity.ok(ApiResponseDto.<Void>builder().success(true).message("Email verified successfully").build());
  }

  @PostMapping("/resend-verification")
  public ResponseEntity<ApiResponseDto<Void>> resendVerification(@RequestParam("email") String email) {
    var user = userDetailsService.getUserByEmail(email);
    authService.sendVerificationEmail(user);
    return ResponseEntity.ok(ApiResponseDto.<Void>builder().success(true).message("Verification email resent").build());
  }

  private UserProfileResponse toProfile(UserEntity user) {
    return UserProfileResponse.builder()
        .id(user.getId().toString())
        .firstName(user.getFirstName())
        .lastName(user.getLastName())
        .username(user.getUsername())
        .email(user.getEmail())
        .phone(user.getPhone())
        .avatarUrl(user.getAvatarUrl())
        .organization(user.getOrganization())
        .roles(user.getRoles().stream().map(role -> role.getName().name()).collect(java.util.stream.Collectors.toSet()))
        .status(user.getStatus())
        .oauthProvider(user.getOauthProvider())
        .emailVerified(user.isEmailVerified())
        .timezone(user.getTimezone())
        .language(user.getLanguage())
        .darkMode(user.isDarkMode())
        .createdAt(user.getCreatedAt())
        .updatedAt(user.getUpdatedAt())
        .lastLogin(user.getLastLogin())
        .build();
  }
}
