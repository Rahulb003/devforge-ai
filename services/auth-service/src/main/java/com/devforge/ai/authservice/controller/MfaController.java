package com.devforge.ai.authservice.controller;

import com.devforge.ai.authservice.dto.ApiResponseDto;
import com.devforge.ai.authservice.dto.MfaCodeRequest;
import com.devforge.ai.authservice.security.UserPrincipal;
import com.devforge.ai.authservice.service.AuthService;
import com.devforge.ai.authservice.service.MfaService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MFA enrolment and management for the signed-in user.
 *
 * <p>Every endpoint acts on the caller's own account, taken from the security context. There is
 * deliberately no user id in any path: accepting one would invite the obvious IDOR, where a
 * caller disables another account's second factor.
 */
@RestController
@RequestMapping("/api/v1/auth/mfa")
@RequiredArgsConstructor
public class MfaController {

  private final MfaService mfaService;
  private final AuthService authService;

  /**
   * Current MFA state, for rendering the security screen.
   *
   * <p>Reports the stored flag rather than inferring it from anything else: a user may legitimately
   * have MFA enabled with zero recovery codes left, and that must not read as "disabled".
   */
  @GetMapping("/status")
  public ResponseEntity<ApiResponseDto<Map<String, Object>>> status(
      @AuthenticationPrincipal UserPrincipal principal) {
    var enabled = authService.findById(principal.getId())
        .map(user -> user.isMfaEnabled())
        .orElse(false);
    return ResponseEntity.ok(ApiResponseDto.<Map<String, Object>>builder()
        .success(true)
        .data(Map.of(
            "enabled", enabled,
            "remainingBackupCodes", mfaService.remainingBackupCodes(principal.getId())))
        .build());
  }

  /**
   * Begins enrolment.
   *
   * <p>Returns the shared secret and provisioning URI. This is the only time the secret is ever
   * returned, and MFA is not yet active — it is switched on by {@code /confirm}.
   */
  @PostMapping("/enrol")
  public ResponseEntity<ApiResponseDto<MfaService.EnrolmentChallenge>> enrol(
      @AuthenticationPrincipal UserPrincipal principal) {
    var challenge = mfaService.beginEnrolment(principal.getId());
    return ResponseEntity.ok(ApiResponseDto.<MfaService.EnrolmentChallenge>builder()
        .success(true)
        .data(challenge)
        .message("Scan the QR code, then confirm with a code from your authenticator.")
        .build());
  }

  /** Confirms enrolment and returns the recovery codes, shown once. */
  @PostMapping("/confirm")
  public ResponseEntity<ApiResponseDto<List<String>>> confirm(
      @AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody MfaCodeRequest request) {
    var codes = mfaService.confirmEnrolment(principal.getId(), request.code());
    return ResponseEntity.ok(ApiResponseDto.<List<String>>builder()
        .success(true)
        .data(codes.codes())
        .message("MFA enabled. Store these recovery codes now — they will not be shown again.")
        .build());
  }

  /** Disables MFA. Requires a valid current code, not merely a live session. */
  @PostMapping("/disable")
  public ResponseEntity<ApiResponseDto<Void>> disable(
      @AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody MfaCodeRequest request) {
    mfaService.disable(principal.getId(), request.code());
    return ResponseEntity.ok(ApiResponseDto.<Void>builder()
        .success(true).message("MFA disabled").build());
  }

  /** Issues a fresh set of recovery codes, invalidating the previous set. */
  @PostMapping("/backup-codes")
  public ResponseEntity<ApiResponseDto<List<String>>> regenerateBackupCodes(
      @AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody MfaCodeRequest request) {
    var codes = mfaService.regenerate(principal.getId(), request.code());
    return ResponseEntity.ok(ApiResponseDto.<List<String>>builder()
        .success(true)
        .data(codes.codes())
        .message("Previous recovery codes are no longer valid.")
        .build());
  }
}
