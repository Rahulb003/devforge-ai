package com.devforge.ai.authservice.controller;

import com.devforge.ai.authservice.dto.ApiResponseDto;
import com.devforge.ai.authservice.dto.SessionResponse;
import com.devforge.ai.authservice.security.UserPrincipal;
import com.devforge.ai.authservice.service.AuthService;
import com.devforge.ai.authservice.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-device session management.
 *
 * <p>All endpoints operate on the caller's own sessions, resolved from the security context.
 * Session ids are only ever looked up together with the owner's user id, so a guessed id from
 * another account resolves to nothing.
 */
@RestController
@RequestMapping("/api/v1/auth/sessions")
@RequiredArgsConstructor
public class SessionController {

  private final SessionService sessionService;
  private final AuthService authService;

  /** Lists live sessions, flagging which one is the caller's current device. */
  @GetMapping
  public ResponseEntity<ApiResponseDto<List<SessionResponse>>> list(
      @AuthenticationPrincipal UserPrincipal principal, HttpServletRequest request) {
    var currentToken = authService.extractRefreshTokenFromRequest(request);
    var sessions = sessionService.listSessions(principal.getId(), currentToken);
    return ResponseEntity.ok(ApiResponseDto.<List<SessionResponse>>builder()
        .success(true).data(sessions).build());
  }

  /** Signs one device out. */
  @DeleteMapping("/{sessionId}")
  public ResponseEntity<ApiResponseDto<Void>> revoke(
      @AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID sessionId) {
    sessionService.revokeSession(principal.getId(), sessionId);
    return ResponseEntity.ok(ApiResponseDto.<Void>builder()
        .success(true).message("Session revoked").build());
  }

  /**
   * Signs out every other device, keeping the current one.
   *
   * <p>This is the "my account may be compromised" action, so it must not log the user out of
   * the device they are using to take it.
   */
  @PostMapping("/revoke-others")
  public ResponseEntity<ApiResponseDto<Map<String, Integer>>> revokeOthers(
      @AuthenticationPrincipal UserPrincipal principal, HttpServletRequest request) {
    var currentToken = authService.extractRefreshTokenFromRequest(request);
    var revoked = sessionService.revokeOtherSessions(principal.getId(), currentToken);
    return ResponseEntity.ok(ApiResponseDto.<Map<String, Integer>>builder()
        .success(true)
        .data(Map.of("revoked", revoked))
        .message("Other sessions revoked")
        .build());
  }
}
