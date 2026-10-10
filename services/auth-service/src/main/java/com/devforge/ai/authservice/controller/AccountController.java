package com.devforge.ai.authservice.controller;

import com.devforge.ai.authservice.dto.ApiResponseDto;
import com.devforge.ai.authservice.security.UserPrincipal;
import com.devforge.ai.authservice.service.AccountService;
import com.devforge.ai.authservice.service.AccountService.ProfileChanges;
import com.devforge.ai.authservice.service.AuthService;
import com.devforge.ai.authservice.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own account. Always the caller, from the security context; never an id parameter. */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AccountController {

  private final AccountService accounts;
  private final AuthService authService;
  private final SessionService sessions;

  public record PasswordChange(String currentPassword, String newPassword) {}

  @PatchMapping("/me")
  public ResponseEntity<ApiResponseDto<Map<String, String>>> updateProfile(
      @AuthenticationPrincipal UserPrincipal principal, @RequestBody ProfileChanges changes) {
    var user = accounts.updateProfile(principal.getId(), changes);
    var profile = new java.util.HashMap<String, String>();
    profile.put("firstName", user.getFirstName());
    profile.put("lastName", user.getLastName());
    profile.put("timezone", user.getTimezone());
    profile.put("language", user.getLanguage());
    return ResponseEntity.ok(ApiResponseDto.<Map<String, String>>builder()
        .success(true).data(profile).message("Profile updated").build());
  }

  /**
   * Changes the password and signs out every other device, keeping this one.
   *
   * <p>Other sessions end because a password change is what someone does when they think the
   * account is compromised, and a stolen session would otherwise outlive the new password.
   */
  @PostMapping("/password")
  public ResponseEntity<ApiResponseDto<Map<String, Integer>>> changePassword(
      @AuthenticationPrincipal UserPrincipal principal, @RequestBody PasswordChange change,
      HttpServletRequest request) {
    accounts.changePassword(principal.getId(), change.currentPassword(), change.newPassword(), request);
    var signedOut = sessions.revokeOtherSessions(
        principal.getId(), authService.extractRefreshTokenFromRequest(request));
    return ResponseEntity.ok(ApiResponseDto.<Map<String, Integer>>builder()
        .success(true)
        .data(Map.of("otherSessionsSignedOut", signedOut))
        .message("Password changed")
        .build());
  }
}
