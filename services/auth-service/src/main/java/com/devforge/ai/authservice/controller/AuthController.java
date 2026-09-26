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
import com.devforge.ai.authservice.service.CustomUserDetailsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
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

  @PostMapping("/signup")
  public ResponseEntity<ApiResponseDto<UserProfileResponse>> signup(@Valid @RequestBody SignupRequest request) {
    var user = authService.registerUser(request.getFirstName(), request.getLastName(), request.getUsername(), request.getEmail(), request.getPassword(), request.getOrganization());
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponseDto.<UserProfileResponse>builder()
        .success(true)
        .data(toProfile(user))
        .message("Signup successful. Verification email sent.")
        .build());
  }

  @PostMapping("/login")
  public ResponseEntity<ApiResponseDto<String>> login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
    try {
      Authentication authentication = authenticationManager.authenticate(
          new UsernamePasswordAuthenticationToken(request.getUsernameOrEmail(), request.getPassword()));
      var accessToken = authService.createAccessToken(authentication);
      var user = (UserPrincipal) authentication.getPrincipal();
      var refreshToken = authService.createRefreshToken(user.toEntity());
      authService.storeRefreshToken(user.toEntity(), refreshToken);
      authService.addRefreshCookie(response, refreshToken);
      return ResponseEntity.ok(ApiResponseDto.<String>builder().success(true).data(accessToken).message("Login successful").build());
    } catch (AuthenticationException ex) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponseDto.<String>builder().success(false).message("Invalid credentials").build());
    }
  }

  @PostMapping("/logout")
  public ResponseEntity<ApiResponseDto<Void>> logout(HttpServletRequest request, HttpServletResponse response, Principal principal) {
    if (principal instanceof UserPrincipal userPrincipal) {
      authService.revokeRefreshToken(userPrincipal.getId());
      authService.clearRefreshCookie(response);
      return ResponseEntity.ok(ApiResponseDto.<Void>builder().success(true).message("Logout successful").build());
    }
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponseDto.<Void>builder().success(false).message("Unauthorized").build());
  }

  @PostMapping("/refresh")
  public ResponseEntity<ApiResponseDto<String>> refresh(HttpServletRequest request, HttpServletResponse response) {
    var refreshToken = authService.extractRefreshTokenFromRequest(request);
    if (refreshToken == null || !authService.validateRefreshToken(refreshToken)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponseDto.<String>builder().success(false).message("Refresh token invalid").build());
    }
    var newAccessToken = authService.refreshAccessToken(refreshToken);
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
