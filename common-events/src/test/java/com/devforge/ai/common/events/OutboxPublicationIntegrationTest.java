package com.devforge.ai.common.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devforge.ai.common.events.outbox.OutboxEventRecorder;
import com.devforge.ai.common.events.outbox.OutboxEventRepository;
import com.devforge.ai.common.events.outbox.OutboxPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The outbox drained against a real broker.
 *
 * <p>This is the test the event backbone did not have. Everything up to now verified that events
 * were <em>staged</em> correctly — rows in a table, with Kafka switched off. That leaves the part
 * most likely to be wrong untested: serialisation, the producer configuration, partition keying,
 * and whether a row is only marked published once the broker has durably accepted it.
 *
 * <p>The broker here is a real in-process Kafka, not a mock, so a genuinely broken producer
 * configuration fails these tests rather than passing them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
// partitions = 1 so the ordering assertion below is meaningful: ordering is per partition,
// and across several there would be nothing to assert.
@EmbeddedKafka(partitions = 1)
@DisplayName("Outbox publication against a real Kafka broker")
class OutboxPublicationIntegrationTest {

  @Autowired private OutboxEventRecorder recorder;
  @Autowired private OutboxPublisher publisher;
  @Autowired private com.devforge.ai.common.events.outbox.OutboxMetrics outboxMetrics;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private EmbeddedKafkaBroker broker;

  private KafkaConsumer<String, String> consumer;

  /**
   * A topic of its own per test.
   *
   * <p>Each test reads with a fresh consumer group from the earliest offset, which is the only way
   * to be sure a record produced moments earlier is not missed. On a shared topic that also
   * replays every record the previous tests left behind, so "exactly one event arrived" quietly
   * becomes "three did". Kafka topics are append-only and there is no per-test truncation, so
   * isolation has to come from the topic name.
   */
  private String topic;

  @BeforeEach
  void setUp() {
    outboxEventRepository.deleteAll();

    topic = KafkaTopics.IDENTITY + ".t" + UUID.randomUUID().toString().replace("-", "");
    broker.addTopics(new NewTopic(topic, 1, (short) 1));

    consumer = new KafkaConsumer<>(assertionConsumerProps(broker));
  }

  @AfterEach
  void tearDown() {
    if (consumer != null) {
      consumer.close();
    }
  }

  @Test
  @DisplayName("a staged event reaches the broker intact and the row is marked published")
  void stagedEventIsPublished() {
    var tenantId = UUID.randomUUID();
    var actorId = UUID.randomUUID();
    consumer.subscribe(List.of(topic));

    // record() is @Transactional(MANDATORY): it must be called inside the business transaction,
    // which is the whole point of the pattern, so the test supplies one.
    var eventId = transactionTemplate.execute(status -> recorder.record(
        topic,
        EventTypes.USER_REGISTERED,
        tenantId,
        actorId,
        "corr-publication-1",
        Map.of("username", "ada", "email", "ada@example.com")));

    // Staged but not yet sent: the publisher is the only thing that talks to Kafka.
    assertThat(outboxEventRepository.findAll()).singleElement()
        .satisfies(row -> assertThat(row.getPublishedAt()).isNull());

    var published = publisher.drainOnce();
    assertThat(published).isEqualTo(1);

    var records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(20), 1);
    assertThat(records.count()).isEqualTo(1);
    ConsumerRecord<String, String> record = records.iterator().next();

    // Keyed by tenant, which is what keeps one organization's events in order on one partition.
    assertThat(record.key()).isEqualTo(tenantId.toString());

    var envelope = readEnvelope(record.value());
    assertThat(envelope.eventId()).isEqualTo(eventId);
    assertThat(envelope.eventType()).isEqualTo(EventTypes.USER_REGISTERED);
    assertThat(envelope.version()).isEqualTo(EventEnvelope.CURRENT_VERSION);
    assertThat(envelope.tenantId()).isEqualTo(tenantId);
    assertThat(envelope.actorId()).isEqualTo(actorId);
    assertThat(envelope.correlationId()).isEqualTo("corr-publication-1");
    assertThat(envelope.source()).isEqualTo("event-backbone-test");
    // Instant must survive the round trip; a timestamp serialised as epoch seconds silently
    // loses sub-second precision and breaks ordering comparisons downstream.
    assertThat(envelope.timestamp()).isNotNull();
    assertThat(envelope.payload()).containsEntry("username", "ada");

    assertThat(outboxEventRepository.findAll()).singleElement().satisfies(row -> {
      assertThat(row.getPublishedAt()).isNotNull();
      assertThat(row.getAttempts()).isEqualTo(1);
      assertThat(row.getLastError()).isNull();
    });
  }

  @Test
  @DisplayName("a rolled-back transaction publishes nothing")
  void rollbackPublishesNothing() {
    consumer.subscribe(List.of(topic));

    // The reason the outbox exists: an event must never announce a change that was undone.
    assertThatThrownBy(() -> transactionTemplate.execute(status -> {
      recorder.record(
          topic,
          EventTypes.USER_REGISTERED,
          UUID.randomUUID(),
          null,
          "corr-rollback",
          Map.of("username", "ghost"));
      throw new IllegalStateException("business rule failed after the event was staged");
    })).isInstanceOf(IllegalStateException.class);

    assertThat(outboxEventRepository.findAll()).isEmpty();
    assertThat(publisher.drainOnce()).isZero();

    var records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(3));
    assertThat(records.count()).isZero();
  }

  @Test
  @DisplayName("staging outside a transaction fails loudly instead of losing atomicity")
  void recordOutsideTransactionIsRejected() {
    // Propagation.MANDATORY. Without a surrounding transaction the event and the state change
    // could commit independently, which is exactly the bug the outbox prevents, so this must
    // not degrade quietly.
    assertThatThrownBy(() -> recorder.record(
        topic,
        EventTypes.USER_REGISTERED,
        UUID.randomUUID(),
        null,
        "corr-no-tx",
        Map.of("username", "ada")))
        .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);

    assertThat(outboxEventRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName("the backlog and the stuck rows are exported as gauges, and fall once drained")
  void backlogIsExported() {
    var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    outboxMetrics.bindTo(registry);

    transactionTemplate.execute(status -> {
      recorder.record(topic, EventTypes.PROJECT_CREATED, UUID.randomUUID(), null,
          "corr-metrics", Map.of("name", "first"));
      recorder.record(topic, EventTypes.PROJECT_CREATED, UUID.randomUUID(), null,
          "corr-metrics", Map.of("name", "second"));
      return null;
    });
    // One row as the publisher leaves it after the alert threshold of failed sends.
    var stuck = outboxEventRepository.findAll().get(0);
    stuck.setAttempts(10);
    outboxEventRepository.save(stuck);

    assertThat(registry.get("devforge.outbox.pending").gauge().value()).isEqualTo(2);
    assertThat(registry.get("devforge.outbox.stuck").gauge().value()).isEqualTo(1);

    assertThat(publisher.drainOnce()).isEqualTo(2);
    assertThat(registry.get("devforge.outbox.pending").gauge().value()).isZero();
    assertThat(registry.get("devforge.outbox.stuck").gauge().value()).isZero();
  }

  @Test
  @DisplayName("events for one tenant share a partition key; separate tenants need not")
  void orderingKeyIsPerTenant() {
    var tenantId = UUID.randomUUID();
    consumer.subscribe(List.of(topic));

    transactionTemplate.execute(status -> {
      recorder.record(topic, EventTypes.PROJECT_CREATED, tenantId, null,
          "corr-order", Map.of("name", "first"));
      recorder.record(topic, EventTypes.PROJECT_UPDATED, tenantId, null,
          "corr-order", Map.of("name", "second"));
      return null;
    });

    assertThat(publisher.drainOnce()).isEqualTo(2);

    var records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(20), 2);
    assertThat(records.count()).isEqualTo(2);

    var keys = new java.util.ArrayList<String>();
    var types = new java.util.ArrayList<String>();
    for (var record : records) {
      keys.add(record.key());
      types.add(readEnvelope(record.value()).eventType());
    }

    assertThat(keys).containsOnly(tenantId.toString());
    // Staged in order, drained in order, so the consumer sees them in order.
    assertThat(types).containsExactly(EventTypes.PROJECT_CREATED, EventTypes.PROJECT_UPDATED);
  }

  @Test
  @DisplayName("a platform-level event with no tenant is still routable")
  void platformEventFallsBackToEventIdAsKey() {
    consumer.subscribe(List.of(topic));

    var eventId = transactionTemplate.execute(status -> recorder.record(
        topic, EventTypes.USER_LOGGED_IN, null, null, "corr-platform",
        Map.of("username", "ada")));

    assertThat(publisher.drainOnce()).isEqualTo(1);

    var records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(20), 1);
    var record = records.iterator().next();

    // Null tenant must not mean a null key: that would send every platform event to one
    // partition chosen by the producer's default, rather than spreading them.
    assertThat(record.key()).isEqualTo(eventId.toString());
  }

  /**
   * Consumer properties for assertions.
   *
   * <p>{@code KafkaTestUtils.consumerProps} defaults the key deserializer to Integer, while our
   * keys are tenant UUIDs as strings — so both must be set explicitly or every poll fails with
   * "Size of data received by IntegerDeserializer is not 4".
   */
  static Map<String, Object> assertionConsumerProps(EmbeddedKafkaBroker broker) {
    var props = KafkaTestUtils.consumerProps(
        broker.getBrokersAsString(), "assertions-" + UUID.randomUUID(), "true");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    return props;
  }

  @SuppressWarnings("unchecked")
  private EventEnvelope<Map<String, Object>> readEnvelope(String json) {
    try {
      return objectMapper.readValue(json, EventEnvelope.class);
    } catch (Exception ex) {
      throw new AssertionError("Published payload was not a readable EventEnvelope: " + json, ex);
    }
  }
}
