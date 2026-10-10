package com.devforge.ai.authservice.events;

import com.devforge.ai.common.events.EventTypes;
import com.devforge.ai.common.events.KafkaTopics;
import com.devforge.ai.common.events.outbox.OutboxEventRecorder;
import com.devforge.ai.authservice.entity.UserEntity;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes identity domain events via the outbox.
 *
 * <p>Every method is {@link Propagation#MANDATORY}: these must be called from inside the
 * transaction that performed the change, so the event and the change are atomic. Called without
 * one, they fail loudly rather than quietly losing the atomicity guarantee.
 *
 * <p>Payloads carry ids and non-sensitive attributes only. Never a password hash, token, TOTP
 * secret or recovery code — an event is copied to every consumer and its topic retains it, so
 * anything put here is effectively broadcast and persisted.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdentityEventPublisher {

  private final OutboxEventRecorder outbox;

  @Transactional(propagation = Propagation.MANDATORY)
  public void userRegistered(UserEntity user) {
    outbox.record(
        KafkaTopics.IDENTITY,
        EventTypes.USER_REGISTERED,
        null,
        user.getId(),
        correlationId(),
        Map.of(
            "userId", user.getId().toString(),
            "username", user.getUsername(),
            "email", user.getEmail(),
            "status", user.getStatus().name(),
            "emailVerified", user.isEmailVerified()));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void userVerified(UserEntity user) {
    outbox.record(
        KafkaTopics.IDENTITY,
        EventTypes.USER_VERIFIED,
        null,
        user.getId(),
        correlationId(),
        Map.of(
            "userId", user.getId().toString(),
            "email", user.getEmail()));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void passwordReset(UserEntity user) {
    outbox.record(
        KafkaTopics.IDENTITY,
        EventTypes.USER_PASSWORD_RESET,
        null,
        user.getId(),
        correlationId(),
        // Deliberately no credential material: only that a reset happened, and when.
        Map.of("userId", user.getId().toString()));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void mfaEnabled(UserEntity user) {
    outbox.record(
        KafkaTopics.IDENTITY, EventTypes.USER_MFA_ENABLED, null, user.getId(), correlationId(),
        Map.of("userId", user.getId().toString()));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void mfaDisabled(UserEntity user) {
    outbox.record(
        KafkaTopics.IDENTITY, EventTypes.USER_MFA_DISABLED, null, user.getId(), correlationId(),
        Map.of("userId", user.getId().toString()));
  }

  /** Only the id: the account it names has just had its personal data erased. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void userDeleted(UserEntity user) {
    outbox.record(
        KafkaTopics.IDENTITY, EventTypes.USER_DELETED, null, user.getId(), correlationId(),
        Map.of("userId", user.getId().toString()));
  }

  /**
   * A security signal other services act on, so it goes to the security topic rather than
   * identity: consumers interested in incidents should not have to subscribe to all of identity.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void refreshTokenReuseDetected(UserEntity user) {
    outbox.record(
        KafkaTopics.SECURITY,
        EventTypes.REFRESH_TOKEN_REUSE_DETECTED,
        null,
        user.getId(),
        correlationId(),
        Map.of(
            "userId", user.getId().toString(),
            "action", "ALL_SESSIONS_REVOKED"));
  }

  /**
   * Correlation id for the current request, if one was established.
   *
   * <p>Read from MDC so an event can be traced back to the HTTP call that caused it without every
   * signature having to thread the value through.
   */
  private String correlationId() {
    return MDC.get("correlationId");
  }
}
