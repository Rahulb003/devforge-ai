package com.devforge.ai.common.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.devforge.ai.common.events.outbox.IdempotentEventProcessor;
import com.devforge.ai.common.events.outbox.ProcessedEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.stereotype.Component;

/**
 * Consumption against a real broker: deduplication, and what happens to an event that can never
 * succeed.
 *
 * <p>Both behaviours were written and unit-tested in isolation, but neither had ever run with a
 * broker delivering the messages. That matters because the failure modes only appear there: the
 * retry/backoff budget, whether a non-retryable exception really bypasses it, and whether the
 * dead-letter record is produced at all.
 *
 * <p>The listener below stands in for a service consumer. It is deliberately thin — the point is
 * the surrounding machinery, not the handler.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EmbeddedKafka(
    partitions = 1,
    topics = {KafkaTopics.NOTIFICATIONS, KafkaTopics.NOTIFICATIONS + KafkaTopics.DLT_SUFFIX})
@DisplayName("Event consumption against a real Kafka broker")
class EventConsumptionIntegrationTest {

  static final String CONSUMER_GROUP = "notification-test-consumer";

  @Autowired private KafkaTemplate<String, String> kafkaTemplate;
  @Autowired private ProcessedEventRepository processedEventRepository;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private RecordingListener listener;
  @Autowired private EmbeddedKafkaBroker broker;
  @Autowired private org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<?, ?> listenerFactory;

  @BeforeEach
  void setUp() {
    listener.reset();
    processedEventRepository.deleteAll();
  }

  @Test
  @DisplayName("an event delivered twice is handled once")
  void duplicateDeliveryIsHandledOnce() throws Exception {
    var envelope = envelope(EventTypes.USER_REGISTERED, Map.of("username", "ada"));
    var json = objectMapper.writeValueAsString(envelope);

    // The same envelope twice, which is what at-least-once delivery actually looks like: the
    // outbox resends after a crash between a successful send and the row being marked published.
    kafkaTemplate.send(KafkaTopics.NOTIFICATIONS, envelope.partitionKey(), json).get(20, TimeUnit.SECONDS);
    kafkaTemplate.send(KafkaTopics.NOTIFICATIONS, envelope.partitionKey(), json).get(20, TimeUnit.SECONDS);

    // Two deliveries observed by the listener...
    listener.awaitDeliveries(2);
    assertThat(listener.deliveries()).isEqualTo(2);

    // ...but the side effect ran once, which is the guarantee that matters.
    assertThat(listener.handlerRuns()).isEqualTo(1);
    assertThat(listener.handledPayloads()).containsExactly("ada");

    assertThat(processedEventRepository.existsByEventIdAndConsumerGroup(
        envelope.eventId(), CONSUMER_GROUP)).isTrue();
    assertThat(processedEventRepository.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("distinct events are each handled, so deduplication is not over-broad")
  void distinctEventsAreBothHandled() throws Exception {
    var first = envelope(EventTypes.USER_REGISTERED, Map.of("username", "ada"));
    var second = envelope(EventTypes.USER_VERIFIED, Map.of("username", "grace"));

    kafkaTemplate.send(KafkaTopics.NOTIFICATIONS, first.partitionKey(),
        objectMapper.writeValueAsString(first)).get(20, TimeUnit.SECONDS);
    kafkaTemplate.send(KafkaTopics.NOTIFICATIONS, second.partitionKey(),
        objectMapper.writeValueAsString(second)).get(20, TimeUnit.SECONDS);

    listener.awaitHandlerRuns(2);
    assertThat(listener.handledPayloads()).containsExactlyInAnyOrder("ada", "grace");
    assertThat(processedEventRepository.count()).isEqualTo(2);
  }

  @Test
  @DisplayName("an event that can never succeed is dead-lettered instead of blocking its partition")
  void poisonEventIsDeadLettered() throws Exception {
    try (var dltConsumer = new KafkaConsumer<String, String>(
        OutboxPublicationIntegrationTest.assertionConsumerProps(broker))) {
      dltConsumer.subscribe(List.of(KafkaTopics.deadLetterTopicFor(KafkaTopics.NOTIFICATIONS)));

      var poison = envelope(EventTypes.USER_REGISTERED, Map.of("username", RecordingListener.POISON));
      kafkaTemplate.send(KafkaTopics.NOTIFICATIONS, poison.partitionKey(),
          objectMapper.writeValueAsString(poison)).get(20, TimeUnit.SECONDS);

      var dltRecords = KafkaTestUtils.getRecords(dltConsumer, Duration.ofSeconds(30), 1);
      assertThat(dltRecords.count()).isEqualTo(1);

      var dead = objectMapper.readValue(dltRecords.iterator().next().value(), EventEnvelope.class);
      assertThat(dead.eventId()).isEqualTo(poison.eventId());

      // IllegalArgumentException is registered as non-retryable, so it must go straight across
      // rather than burning the two-minute backoff budget first. A single attempt is the proof.
      assertThat(listener.deliveries()).isEqualTo(1);

      // Nothing was marked processed: the handler threw, so its transaction — including the
      // marker — rolled back. Recording it would permanently skip the event.
      assertThat(processedEventRepository.existsByEventIdAndConsumerGroup(
          poison.eventId(), CONSUMER_GROUP)).isFalse();
    }
  }

  @Test
  @DisplayName("a healthy event behind a poison one is still processed")
  void partitionIsNotBlockedByAPoisonEvent() throws Exception {
    var poison = envelope(EventTypes.USER_REGISTERED, Map.of("username", RecordingListener.POISON));
    var healthy = envelope(EventTypes.USER_VERIFIED, Map.of("username", "grace"));

    // Same partition key, so these are strictly ordered behind one another. If the poison event
    // were retried forever, the healthy one would never arrive — that is the outage this design
    // is meant to avoid, and it is worth asserting rather than assuming.
    var key = poison.partitionKey();
    kafkaTemplate.send(KafkaTopics.NOTIFICATIONS, key,
        objectMapper.writeValueAsString(poison)).get(20, TimeUnit.SECONDS);
    kafkaTemplate.send(KafkaTopics.NOTIFICATIONS, key,
        objectMapper.writeValueAsString(healthy)).get(20, TimeUnit.SECONDS);

    listener.awaitHandlerRuns(1);
    assertThat(listener.handledPayloads()).containsExactly("grace");
  }

  private EventEnvelope<Map<String, Object>> envelope(String eventType, Map<String, Object> payload) {
    return new EventEnvelope<>(
        UUID.randomUUID(),
        eventType,
        EventEnvelope.CURRENT_VERSION,
        Instant.now(),
        "event-backbone-test",
        UUID.randomUUID(),
        null,
        "corr-consumption",
        payload);
  }

  @TestConfiguration
  static class Listeners {
    @Bean
    RecordingListener recordingListener(
        IdempotentEventProcessor processor, ObjectMapper objectMapper) {
      return new RecordingListener(processor, objectMapper);
    }
  }

  /**
   * Stands in for a service consumer, recording what it was asked to do.
   *
   * <p>Separates two counts that are easy to conflate: how many times Kafka <em>delivered</em> a
   * record, and how many times the business handler actually <em>ran</em>. Deduplication is the
   * gap between them.
   */
  @Component
  static class RecordingListener {

    static final String POISON = "poison";

    private final IdempotentEventProcessor processor;
    private final ObjectMapper objectMapper;

    private final AtomicInteger deliveries = new AtomicInteger();
    private final AtomicInteger handlerRuns = new AtomicInteger();
    private final List<String> handledPayloads = new CopyOnWriteArrayList<>();
    private final Map<String, Boolean> seen = new ConcurrentHashMap<>();

    RecordingListener(IdempotentEventProcessor processor, ObjectMapper objectMapper) {
      this.processor = processor;
      this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = KafkaTopics.NOTIFICATIONS, groupId = CONSUMER_GROUP)
    @SuppressWarnings("unchecked")
    void onEvent(String json) throws Exception {
      deliveries.incrementAndGet();
      EventEnvelope<Map<String, Object>> envelope =
          objectMapper.readValue(json, EventEnvelope.class);

      processor.processOnce(envelope, CONSUMER_GROUP, e -> {
        var username = String.valueOf(e.payload().get("username"));
        if (POISON.equals(username)) {
          // Non-retryable by configuration: a malformed payload cannot be fixed by trying again.
          throw new IllegalArgumentException("unprocessable payload for event " + e.eventId());
        }
        handlerRuns.incrementAndGet();
        handledPayloads.add(username);
      });
    }

    void reset() {
      deliveries.set(0);
      handlerRuns.set(0);
      handledPayloads.clear();
      seen.clear();
    }

    int deliveries() {
      return deliveries.get();
    }

    int handlerRuns() {
      return handlerRuns.get();
    }

    List<String> handledPayloads() {
      return List.copyOf(handledPayloads);
    }

    void awaitDeliveries(int expected) {
      await(() -> deliveries.get() >= expected, "deliveries to reach " + expected
          + " (saw " + deliveries.get() + ")");
    }

    void awaitHandlerRuns(int expected) {
      await(() -> handlerRuns.get() >= expected, "handler runs to reach " + expected
          + " (saw " + handlerRuns.get() + ")");
    }

    /**
     * Polls rather than sleeping a fixed time. Consumer-group assignment takes an unpredictable
     * moment on a cold broker, and a fixed sleep is either flaky or needlessly slow.
     */
    private void await(java.util.function.BooleanSupplier condition, String description) {
      var deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (System.nanoTime() < deadline) {
        if (condition.getAsBoolean()) {
          return;
        }
        try {
          Thread.sleep(100);
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          throw new AssertionError("Interrupted waiting for " + description, ex);
        }
      }
      throw new AssertionError("Timed out waiting for " + description);
    }
  }

  @org.junit.jupiter.api.Test
  @DisplayName("the real listener factory retries authorization failures")
  void listenerFactoryRetriesAuthFailures() {
    // Checked on the factory the services actually get, not only on the environment: a property
    // name typo would set nothing and leave listeners dying on the first refusal.
    org.assertj.core.api.Assertions.assertThat(
            listenerFactory.getContainerProperties().getAuthExceptionRetryInterval())
        .isEqualTo(java.time.Duration.ofSeconds(10));
  }
}
