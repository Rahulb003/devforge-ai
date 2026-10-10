package com.devforge.ai.authservice.service;

import com.devforge.ai.authservice.entity.AuditLogEntity;
import com.devforge.ai.authservice.entity.LoginHistoryEntity;
import com.devforge.ai.authservice.entity.UserEntity;
import com.devforge.ai.authservice.repository.AuditLogRepository;
import com.devforge.ai.authservice.repository.LoginHistoryRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the security audit trail.
 *
 * <p>{@code AuditLogEntity} and {@code LoginHistoryEntity} were mapped and migrated but never
 * written to, so the platform kept no record of authentication activity at all.
 *
 * <p>Every method runs in {@link Propagation#REQUIRES_NEW}. A failed login is recorded on a path
 * that then rejects the request, and rate-limit accounting reads these rows back — if the audit
 * write shared the caller's transaction it would roll back with it, losing exactly the events
 * that matter most.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

  public static final String LOGIN_TYPE_PASSWORD = "PASSWORD";
  public static final String LOGIN_TYPE_OAUTH = "OAUTH";
  public static final String LOGIN_TYPE_REFRESH = "REFRESH";

  public static final String STATUS_SUCCESS = "SUCCESS";
  public static final String STATUS_FAILURE = "FAILURE";

  // Audit actions.
  public static final String ACTION_SIGNUP = "USER_SIGNUP";
  public static final String ACTION_EMAIL_VERIFIED = "EMAIL_VERIFIED";
  public static final String ACTION_LOGIN_SUCCESS = "LOGIN_SUCCESS";
  public static final String ACTION_LOGIN_FAILURE = "LOGIN_FAILURE";
  public static final String ACTION_LOGIN_BLOCKED = "LOGIN_BLOCKED";
  public static final String ACTION_LOGOUT = "LOGOUT";
  public static final String ACTION_TOKEN_REFRESHED = "TOKEN_REFRESHED";
  public static final String ACTION_TOKEN_REUSE_DETECTED = "REFRESH_TOKEN_REUSE_DETECTED";
  public static final String ACTION_PASSWORD_RESET_REQUESTED = "PASSWORD_RESET_REQUESTED";
  public static final String ACTION_PASSWORD_RESET_COMPLETED = "PASSWORD_RESET_COMPLETED";

  private static final int MAX_USER_AGENT_LENGTH = 500;
  private static final int MAX_DETAILS_LENGTH = 2000;

  private final AuditLogRepository auditLogRepository;
  private final LoginHistoryRepository loginHistoryRepository;
  private final com.devforge.ai.authservice.repository.AuditChainHeadRepository chainHeads;

  /**
   * Records an auditable action.
   *
   * <p>{@code details} is free text written by us. Never pass a password, token, or any other
   * credential: these rows are long-lived and widely readable.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(UserEntity user, String action, String ipAddress, String details) {
    try {
      var entry = AuditLogEntity.builder()
          .user(user)
          .action(action)
          .entityType(user != null ? "User" : null)
          .entityId(user != null ? user.getId().toString() : null)
          .ipAddress(truncate(ipAddress, 45))
          .details(truncate(details, MAX_DETAILS_LENGTH))
          .createdAt(AuditChain.storable(java.time.Instant.now()))
          .build();
      // The head is locked for this transaction, so concurrent writers take turns at the end of
      // the chain rather than both claiming the next position.
      chainHeads.createIfAbsent(AuditChain.KEY, AuditChain.GENESIS);
      var head = chainHeads.lock(AuditChain.KEY).orElseThrow();
      var sequence = head.getLastSequence() + 1;
      entry.setChainSequence(sequence);
      entry.setPreviousHash(head.getLastHash());
      entry.setEntryHash(AuditChain.hash(head.getLastHash(), sequence, entry));
      auditLogRepository.save(entry);
      head.setLastSequence(sequence);
      head.setLastHash(entry.getEntryHash());
    } catch (RuntimeException ex) {
      // Auditing must never take down the request path it observes.
      log.error("Failed to write audit log for action {}", action, ex);
    }
  }

  /**
   * Records a login attempt.
   *
   * <p>{@code login_history.user_id} is NOT NULL, so an attempt against an unknown username
   * cannot be recorded here — and deliberately should not be, since a per-username row would
   * itself become an account-enumeration signal. Those attempts belong in IP-level rate
   * limiting instead.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordLoginAttempt(
      UserEntity user, String loginType, String status, String failureReason, HttpServletRequest request) {
    if (user == null) {
      return;
    }
    try {
      loginHistoryRepository.save(LoginHistoryEntity.builder()
          .user(user)
          .ipAddress(truncate(clientIp(request), 45))
          .userAgent(truncate(header(request, "User-Agent"), MAX_USER_AGENT_LENGTH))
          .loginType(loginType)
          .status(status)
          .failureReason(truncate(failureReason, 255))
          .build());
    } catch (RuntimeException ex) {
      log.error("Failed to write login history for user {}", user.getId(), ex);
    }
  }

  /**
   * Best-effort client IP.
   *
   * <p>Returns the socket peer address. {@code X-Forwarded-For} is deliberately ignored: it is
   * caller-controlled, and trusting it unconditionally lets an attacker rotate the header to
   * defeat per-IP rate limiting. Once the gateway is in front of these services and its address
   * is known, add an explicit trusted-proxy check and parse the header then.
   */
  public String clientIp(HttpServletRequest request) {
    return request != null ? request.getRemoteAddr() : null;
  }

  private String header(HttpServletRequest request, String name) {
    return request != null ? request.getHeader(name) : null;
  }

  private String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }
}
