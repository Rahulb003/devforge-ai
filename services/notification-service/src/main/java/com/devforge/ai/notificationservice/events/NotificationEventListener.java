package com.devforge.ai.notificationservice.events;

import com.devforge.ai.common.events.EventEnvelope;
import com.devforge.ai.common.events.KafkaTopics;
import com.devforge.ai.common.events.outbox.IdempotentEventProcessor;
import com.devforge.ai.notificationservice.service.NotificationWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The platform's first production event consumer.
 *
 * <p>Subscribes to the topics that actually have producers today — identity and security from
 * auth-service, tasks from task-service. Project events are deliberately absent: project-service
 * publishes none yet, and a listener for an event nobody emits is dead code that looks like a
 * feature.
 *
 * <p>Every handler runs through {@link IdempotentEventProcessor}. That is not optional here:
 * delivery is at-least-once, so without deduplication an ordinary consumer-group rebalance would
 * leave a user with two copies of the same notification.
 *
 * <p>Disabled with {@code devforge.kafka.enabled=false}, which the standalone profile uses so the
 * platform can run with no broker at all.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "devforge.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationEventListener {

  /**
   * The deduplication scope.
   *
   * <p>Must stay stable across deployments: changing it makes every past event look unprocessed,
   * and the service would replay history as new notifications.
   */
  public static final String CONSUMER_GROUP = "notification-service";

  private final IdempotentEventProcessor processor;
  private final NotificationWriter writer;
  private final ObjectMapper objectMapper;

  @KafkaListener(
      topics = {KafkaTopics.IDENTITY, KafkaTopics.SECURITY, KafkaTopics.TASKS, KafkaTopics.REPOSITORIES,
          KafkaTopics.PROJECTS},
      groupId = CONSUMER_GROUP)
  public void onEvent(String message) {
    var envelope = parse(message);

    // Restored so the log lines this consumer writes can be tied back to the HTTP request in
    // another service that caused the event.
    var correlationId = envelope.correlationId();
    if (correlationId != null) {
      MDC.put("correlationId", correlationId);
    }
    try {
      processor.processOnce(envelope, CONSUMER_GROUP, writer::write);
    } finally {
      // Listener threads are pooled and long-lived, so a value left behind would be attributed
      // to whatever event this thread handles next.
      MDC.remove("correlationId");
    }
  }

  /**
   * Parses the envelope, failing in a way that routes to the dead-letter topic.
   *
   * <p>{@link IllegalArgumentException} is registered as non-retryable in the shared Kafka error
   * handler, so an unreadable message is set aside immediately instead of being retried until the
   * backoff budget runs out — during which it would block its partition and hold up every valid
   * event behind it.
   */
  @SuppressWarnings("unchecked")
  private EventEnvelope<Map<String, Object>> parse(String message) {
    try {
      return objectMapper.readValue(message, EventEnvelope.class);
    } catch (Exception ex) {
      log.error("Unreadable event message, routing to the dead-letter topic: {}", ex.getMessage());
      throw new IllegalArgumentException("Malformed event envelope", ex);
    }
  }
}
