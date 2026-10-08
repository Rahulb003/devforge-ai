package com.devforge.ai.authservice.controller;

import com.devforge.ai.authservice.dto.ApiResponseDto;
import com.devforge.ai.authservice.dto.ChangePasswordRequest;
import com.devforge.ai.authservice.dto.ForgotPasswordRequest;
import com.devforge.ai.authservice.dto.LoginRequest;
import com.devforge.ai.authservice.dto.LoginResult;
import com.devforge.ai.authservice.dto.MfaVerifyRequest;
import com.devforge.ai.authservice.dto.PasswordResetRequest;
import com.devforge.ai.authservice.dto.SignupRequest;
import com.devforge.ai.authservice.dto.UserProfileResponse;
import com.devforge.ai.authservice.entity.UserEntity;
import com.devforge.ai.authservice.security.UserPrincipal;
import com.devforge.ai.authservice.service.AuthService;
import com.devforge.ai.authservice.service.AuditService;
import com.devforge.ai.authservice.service.CustomUserDetailsService;
import com.devforge.ai.authservice.service.MfaService;
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
  private final MfaService mfaService;

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
  public ResponseEntity<ApiResponseDto<LoginResult>> login(
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
      var user = (UserPrincipal) authentication.getPrincipal();
      var entity = authService.findById(user.getId()).orElseThrow();

      // A correct password is only the first factor. When MFA is on, no access or refresh
      // token is issued here: the caller gets a short-lived challenge token and must prove
      // the second factor before any usable credential exists.
      if (entity.isMfaEnabled()) {
        auditService.record(entity, "LOGIN_MFA_CHALLENGED",
            auditService.clientIp(httpRequest), "Password accepted; awaiting second factor.");
        return ResponseEntity.ok(ApiResponseDto.<LoginResult>builder()
            .success(true)
            .data(LoginResult.mfaChallenge(authService.createMfaChallengeToken(entity)))
            .message("Multi-factor authentication required")
            .build());
      }

      var accessToken = authService.createAccessToken(authentication);
      issueSession(entity.getId(), httpRequest, response);
      recordLoginSuccess(entity, httpRequest);

      return ResponseEntity.ok(ApiResponseDto.<LoginResult>builder()
          .success(true).data(LoginResult.authenticated(deliverAccessToken(httpRequest, response, accessToken)))
          .message("Login successful")
          .build());
    } catch (AuthenticationException ex) {
      // Recorded against the account when we know it; this is what the throttle counts.
      auditService.recordLoginAttempt(existingUser, AuditService.LOGIN_TYPE_PASSWORD,
          AuditService.STATUS_FAILURE, ex.getClass().getSimpleName(), httpRequest);
      auditService.record(existingUser, AuditService.ACTION_LOGIN_FAILURE,
          auditService.clientIp(httpRequest), "Password login failed.");
      return unauthorized();
    }
  }

  private ResponseEntity<ApiResponseDto<LoginResult>> unauthorized() {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(ApiResponseDto.<LoginResult>builder()
            .success(false).message("Invalid credentials").build());
  }

  /**
   * Second step of login: exchanges an MFA challenge token plus a code for real credentials.
   *
   * <p>The challenge token only identifies which half-finished login this is. It carries
   * {@code typ=mfa}, so it cannot authenticate an API call on its own even before the code is
   * checked.
   */
  @PostMapping("/login/mfa")
  public ResponseEntity<ApiResponseDto<LoginResult>> verifyMfa(
      @Valid @RequestBody MfaVerifyRequest request,
      HttpServletRequest httpRequest,
      HttpServletResponse response) {

    var userId = authService.userIdFromMfaChallenge(request.challengeToken());
    if (userId == null) {
      return unauthorized();
    }
    var entity = authService.findById(userId).orElse(null);
    if (entity == null || !entity.isMfaEnabled()) {
      return unauthorized();
    }

    // Second-factor attempts count against the same throttle as passwords; otherwise the
    // six-digit code space could be brute-forced freely once a password was known.
    if (loginAttemptService.isBlocked(userId)) {
      auditService.record(entity, AuditService.ACTION_LOGIN_BLOCKED,
          auditService.clientIp(httpRequest), "MFA verification refused: too many failures.");
      return unauthorized();
    }

    if (!mfaService.verifyCode(entity, request.code())) {
      auditService.recordLoginAttempt(entity, AuditService.LOGIN_TYPE_PASSWORD,
          AuditService.STATUS_FAILURE, "Invalid MFA code", httpRequest);
      auditService.record(entity, "MFA_VERIFICATION_FAILED",
          auditService.clientIp(httpRequest), "Invalid second factor submitted.");
      return unauthorized();
    }

    var principal = UserPrincipal.fromEntity(entity);
    var authentication = new UsernamePasswordAuthenticationToken(
        principal, null, principal.getAuthorities());
    var accessToken = authService.createAccessToken(authentication);
    issueSession(entity.getId(), httpRequest, response);
    recordLoginSuccess(entity, httpRequest);

    return ResponseEntity.ok(ApiResponseDto.<LoginResult>builder()
        .success(true).data(LoginResult.authenticated(deliverAccessToken(httpRequest, response, accessToken)))
        .message("Login successful").build());
  }

  /**
   * Sets the access cookie, and returns the token for the body only to non-browser clients.
   *
   * <p>A browser gets the token as an HttpOnly cookie and nothing else, or script could read it from
   * the body and the cookie would protect nothing. The SPA marks itself with X-Requested-With. A
   * script that leaves the header off to get the token back is refused by the gateway first: it
   * rejects any cookie-carrying write without that header, and refresh always carries one.
   */
  private String deliverAccessToken(
      HttpServletRequest request, HttpServletResponse response, String token) {
    authService.addAccessCookie(response, token);
    return "XMLHttpRequest".equals(request.getHeader("X-Requested-With")) ? null : token;
  }

  /** Creates a device-scoped session and sets the refresh cookie. */
  private void issueSession(
      java.util.UUID userId, HttpServletRequest httpRequest, HttpServletResponse response) {
    var userAgent = httpRequest.getHeader("User-Agent");
    var refreshToken = authService.issueAndStoreRefreshToken(
        userId,
        DeviceLabeller.describe(userAgent),
        userAgent,
        auditService.clientIp(httpRequest));
    authService.addRefreshCookie(response, refreshToken);
  }

  private void recordLoginSuccess(
      com.devforge.ai.authservice.entity.UserEntity entity, HttpServletRequest httpRequest) {
    auditService.recordLoginAttempt(entity, AuditService.LOGIN_TYPE_PASSWORD,
        AuditService.STATUS_SUCCESS, null, httpRequest);
    auditService.record(entity, AuditService.ACTION_LOGIN_SUCCESS,
        auditService.clientIp(httpRequest), "Login succeeded.");
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
    authService.clearAccessCookie(response);
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
      // Clear the cookies so a client holding a dead token stops replaying it.
      authService.clearRefreshCookie(response);
      authService.clearAccessCookie(response);
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(ApiResponseDto.<String>builder().success(false).message("Refresh token invalid").build());
    }
    return ResponseEntity.ok(ApiResponseDto.<String>builder().success(true).data(deliverAccessToken(request, response, newAccessToken)).message("Token refreshed").build());
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

  /**
   * The signed-in user's own profile.
   *
   * <p>There is deliberately no user id parameter: this always returns the caller, taken from
   * the security context. Accepting an id would be an invitation to read other accounts.
   */
  @GetMapping("/me")
  public ResponseEntity<ApiResponseDto<UserProfileResponse>> me(
      @AuthenticationPrincipal UserPrincipal principal) {
    if (principal == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(ApiResponseDto.<UserProfileResponse>builder()
              .success(false).message("Unauthorized").build());
    }
    return authService.findById(principal.getId())
        .map(user -> ResponseEntity.ok(ApiResponseDto.<UserProfileResponse>builder()
            .success(true).data(toProfile(user)).build()))
        .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ApiResponseDto.<UserProfileResponse>builder()
                .success(false).message("Unauthorized").build()));
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
