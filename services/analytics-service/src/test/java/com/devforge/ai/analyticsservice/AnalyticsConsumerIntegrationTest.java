package com.devforge.ai.analyticsservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.devforge.ai.analyticsservice.repository.ProjectDailyMetricsRepository;
import com.devforge.ai.common.events.EventEnvelope;
import com.devforge.ai.common.events.KafkaTopics;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

/**
 * Counting driven by a real broker.
 *
 * <p>Deduplication is the reason this runs against Kafka rather than calling the recorder directly.
 * A duplicate notification is visible and dismissable; a duplicate increment silently inflates a
 * number that nobody can later tell is wrong, so "the same event twice counts once" has to be
 * proven where redelivery actually happens.
 */
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {KafkaTopics.TASKS, KafkaTopics.REPOSITORIES, KafkaTopics.PROJECTS})
@TestPropertySource(properties = {
    "devforge.kafka.enabled=true",
    "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
})
@DisplayName("Analytics consumer against a real Kafka broker")
class AnalyticsConsumerIntegrationTest {

  @Autowired private KafkaTemplate<String, String> kafkaTemplate;
  @Autowired private ProjectDailyMetricsRepository metrics;
  @Autowired private com.devforge.ai.analyticsservice.repository.AuditEntryRepository auditEntries;
  @Autowired private ObjectMapper objectMapper;

  private final UUID organizationId = UUID.randomUUID();
  private UUID projectId;

  @BeforeEach
  void setUp() {
    metrics.deleteAll();
    auditEntries.deleteAll();
    // A project per test, so one test's counters cannot be mistaken for another's.
    projectId = UUID.randomUUID();
  }

  private EventEnvelope<Map<String, Object>> event(String type, Instant when) {
    return new EventEnvelope<>(
        UUID.randomUUID(),
        type,
        EventEnvelope.CURRENT_VERSION,
        when,
        "task-service",
        organizationId,
        UUID.randomUUID(),
        "corr-analytics",
        Map.of("projectId", projectId.toString(), "taskId", UUID.randomUUID().toString()));
  }

  private void publish(String topic, EventEnvelope<Map<String, Object>> envelope) {
    try {
      kafkaTemplate
          .send(topic, envelope.partitionKey(), objectMapper.writeValueAsString(envelope))
          .get(20, TimeUnit.SECONDS);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while publishing", ex);
    } catch (Exception ex) {
      throw new AssertionError("Failed to publish", ex);
    }
  }

  private LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  @Test
  @DisplayName("every event is written to the audit log, including types no metric counts")
  void everyEventIsAudited() {
    var now = Instant.now();
    var granted = new EventEnvelope<Map<String, Object>>(
        UUID.randomUUID(), "ProjectMemberAdded", EventEnvelope.CURRENT_VERSION, now,
        "project-service", organizationId, UUID.randomUUID(), "corr-audit",
        Map.of("projectId", projectId.toString(), "userId", UUID.randomUUID().toString(),
            "role", "VIEWER"));
    publish(KafkaTopics.PROJECTS, granted);
    publish(KafkaTopics.TASKS, event("TaskCreated", now));
    // Delivered twice, recorded once.
    publish(KafkaTopics.PROJECTS, granted);

    await(() -> auditEntries.findAll().size() == 2, "both distinct events to be audited");
    settle();

    var entries = auditEntries.findAll();
    assertThat(entries).hasSize(2);
    var membership = entries.stream()
        .filter(e -> e.getEventType().equals("ProjectMemberAdded")).findFirst().orElseThrow();
    assertThat(membership.getProjectId()).isEqualTo(projectId);
    assertThat(membership.getOrganizationId()).isEqualTo(organizationId);
    assertThat(membership.getActorId()).isEqualTo(granted.actorId());
    assertThat(membership.getSource()).isEqualTo("project-service");
    assertThat(membership.getDetails()).contains("\"role\":\"VIEWER\"");
  }

  @Test
  @DisplayName("counts task and repository activity into the right day")
  void countsActivity() {
    var now = Instant.now();
    publish(KafkaTopics.TASKS, event("TaskCreated", now));
    publish(KafkaTopics.TASKS, event("TaskCreated", now));
    publish(KafkaTopics.TASKS, event("TaskCompleted", now));
    publish(KafkaTopics.REPOSITORIES, event("RepositoryPushed", now));

    await(() -> metrics.findByProjectIdAndMetricDate(projectId, today())
        .map(m -> m.getTasksCreated() == 2 && m.getTasksCompleted() == 1 && m.getCommits() == 1)
        .orElse(false), "all four events to be counted");

    var bucket = metrics.findByProjectIdAndMetricDate(projectId, today()).orElseThrow();
    assertThat(bucket.getOrganizationId()).isEqualTo(organizationId);
    assertThat(bucket.getTasksAssigned()).isZero();
  }

  @Test
  @DisplayName("the same event delivered twice counts once")
  void duplicateCountsOnce() {
    var envelope = event("TaskCreated", Instant.now());

    // What at-least-once delivery looks like in practice.
    publish(KafkaTopics.TASKS, envelope);
    publish(KafkaTopics.TASKS, envelope);

    await(() -> metrics.findByProjectIdAndMetricDate(projectId, today()).isPresent(),
        "the first event to be counted");
    settle();

    // The whole reason this service deduplicates: a double count is invisible afterwards.
    assertThat(metrics.findByProjectIdAndMetricDate(projectId, today()).orElseThrow().getTasksCreated())
        .isEqualTo(1);
  }

  @Test
  @DisplayName("activity is bucketed by when it happened, not when it was consumed")
  void bucketsByEventTime() {
    var threeDaysAgo = Instant.now().minus(3, ChronoUnit.DAYS);
    publish(KafkaTopics.TASKS, event("TaskCreated", threeDaysAgo));

    var day = threeDaysAgo.atZone(ZoneOffset.UTC).toLocalDate();
    await(() -> metrics.findByProjectIdAndMetricDate(projectId, day).isPresent(),
        "the event to land on the day it happened");

    // Otherwise every consumer outage would leave a visible spike on the wrong date.
    assertThat(metrics.findByProjectIdAndMetricDate(projectId, today())).isEmpty();
  }

  @Test
  @DisplayName("an event type with no metric is ignored rather than dead-lettered")
  void unknownEventTypeIsIgnored() {
    publish(KafkaTopics.TASKS, event("TaskMoved", Instant.now()));

    settle();
    // A consumer that rejected unknown types would start failing the moment a producer shipped one.
    assertThat(metrics.count()).isZero();
  }

  @Test
  @DisplayName("an event with no project is skipped without blocking the ones behind it")
  void malformedEventDoesNotBlockThePartition() {
    var orphan = new EventEnvelope<Map<String, Object>>(
        UUID.randomUUID(), "TaskCreated", EventEnvelope.CURRENT_VERSION, Instant.now(),
        "task-service", organizationId, null, "corr", Map.of());
    publish(KafkaTopics.TASKS, orphan);
    publish(KafkaTopics.TASKS, event("TaskCreated", Instant.now()));

    await(() -> metrics.findByProjectIdAndMetricDate(projectId, today())
        .map(m -> m.getTasksCreated() == 1)
        .orElse(false), "the valid event behind the orphan to be counted");
  }

  // ---------------------------------------------------------------- helpers

  private void await(BooleanSupplier condition, String description) {
    var deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      pause(100);
    }
    throw new AssertionError("Timed out waiting for " + description);
  }

  /**
   * Gives the consumer time to do something wrong.
   *
   * <p>Needed where the assertion is that nothing happened: with no state change to wait for, the
   * only way to tell "correctly ignored" from "not processed yet" is to let the listener finish.
   */
  private void settle() {
    pause(3_000);
  }

  private void pause(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted", ex);
    }
  }
}
