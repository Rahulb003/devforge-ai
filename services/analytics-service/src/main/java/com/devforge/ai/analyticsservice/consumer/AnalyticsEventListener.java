package com.devforge.ai.analyticsservice.consumer;

import com.devforge.ai.common.events.EventEnvelope;
import com.devforge.ai.common.events.KafkaTopics;
import com.devforge.ai.common.events.outbox.IdempotentEventProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The platform's second production consumer, and the first that counts things.
 *
 * <p>Deduplication matters more here than anywhere else so far. Notifications are idempotent by
 * nature — a duplicate is visible and can be dismissed — but these are counters: processing one
 * event twice silently inflates a number, and nobody looking at the chart afterwards can tell it is
 * wrong. {@link IdempotentEventProcessor} writes its marker in the same transaction as the
 * increment, so the two commit or roll back together.
 *
 * <p>Its own consumer group, separate from notification-service's: Kafka delivers to each group
 * independently, so both services see every event and neither can starve the other.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "devforge.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class AnalyticsEventListener {

  /**
   * The deduplication scope.
   *
   * <p>Must stay stable across deployments: changing it makes every past event look unprocessed,
   * and the service would replay history and double every counter.
   */
  public static final String CONSUMER_GROUP = "analytics-service";

  private final IdempotentEventProcessor processor;
  private final MetricsRecorder recorder;
  private final AuditRecorder audit;
  private final ObjectMapper objectMapper;

  @KafkaListener(
      topics = {KafkaTopics.TASKS, KafkaTopics.REPOSITORIES, KafkaTopics.PROJECTS},
      groupId = CONSUMER_GROUP)
  public void onEvent(String message) {
    var envelope = parse(message);

    var correlationId = envelope.correlationId();
    if (correlationId != null) {
      MDC.put("correlationId", correlationId);
    }
    try {
      // Both in the one transaction the processor opens: counted and audited exactly once.
      processor.processOnce(envelope, CONSUMER_GROUP, event -> {
        recorder.record(event);
        audit.record(event);
      });
    } finally {
      // Listener threads are pooled, so a value left behind would be attributed to the next event.
      MDC.remove("correlationId");
    }
  }

  /**
   * Parses the envelope, failing in a way that routes to the dead-letter topic.
   *
   * <p>{@link IllegalArgumentException} is registered as non-retryable in the shared error handler,
   * so an unreadable message is set aside at once instead of blocking its partition while the
   * backoff budget runs out.
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
