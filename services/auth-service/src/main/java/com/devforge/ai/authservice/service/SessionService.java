package com.devforge.ai.authservice.service;

import com.devforge.ai.authservice.dto.SessionResponse;
import com.devforge.ai.authservice.repository.RefreshTokenRepository;
import com.devforge.ai.authservice.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lets a user see and revoke their own sessions.
 *
 * <p>Every method is scoped by the caller's user id. Session ids are only ever resolved through
 * {@code findByIdAndUserId}, so one user cannot revoke another's session by guessing an id — the
 * same scoped-lookup rule applied to projects.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

  private final RefreshTokenRepository refreshTokenRepository;
  private final UserRepository userRepository;
  private final AuditService auditService;

  /**
   * Live sessions for a user.
   *
   * @param currentToken the refresh token presented on this request, used to mark which entry is
   *     "this device". Never returned to the client.
   */
  @Transactional(readOnly = true)
  public List<SessionResponse> listSessions(UUID userId, String currentToken) {
    return refreshTokenRepository.findByUserIdAndRevokedFalseOrderByCreatedAtDesc(userId).stream()
        .filter(token -> token.getExpiresAt().isAfter(Instant.now()))
        .map(token -> new SessionResponse(
            token.getId(),
            token.getDeviceLabel(),
            token.getUserAgent(),
            token.getIpAddress(),
            token.getCreatedAt(),
            token.getLastUsedAt(),
            token.getExpiresAt(),
            // The token value itself is never exposed; only whether it is this one.
            token.getToken().equals(currentToken)))
        .toList();
  }

  /** Revokes one session. A session belonging to another user is reported as absent. */
  @Transactional
  public void revokeSession(UUID userId, UUID sessionId) {
    var session = refreshTokenRepository.findByIdAndUserId(sessionId, userId)
        .orElseThrow(() -> new com.devforge.ai.common.exception.ResourceNotFoundException(
            "Session not found"));
    refreshTokenRepository.delete(session);

    userRepository.findById(userId).ifPresent(user ->
        auditService.record(user, "SESSION_REVOKED", null,
            "Session %s revoked by the user.".formatted(sessionId)));
    log.info("Session {} revoked for user {}", sessionId, userId);
  }

  /**
   * Revokes every session except the one making the request.
   *
   * <p>This is the "I think my account is compromised" action, so it must not log the user out of
   * the device they are using to take it.
   */
  @Transactional
  public int revokeOtherSessions(UUID userId, String currentToken) {
    var others = refreshTokenRepository.findByUserIdAndRevokedFalseOrderByCreatedAtDesc(userId)
        .stream()
        .filter(token -> !token.getToken().equals(currentToken))
        .toList();
    refreshTokenRepository.deleteAll(others);

    userRepository.findById(userId).ifPresent(user ->
        auditService.record(user, "ALL_OTHER_SESSIONS_REVOKED", null,
            "%d other session(s) revoked.".formatted(others.size())));
    log.info("Revoked {} other sessions for user {}", others.size(), userId);
    return others.size();
  }
}
