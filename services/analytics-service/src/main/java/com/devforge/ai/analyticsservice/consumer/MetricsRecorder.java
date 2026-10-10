package com.devforge.ai.analyticsservice.consumer;

import com.devforge.ai.analyticsservice.entity.ProjectDailyMetricsEntity;
import com.devforge.ai.analyticsservice.repository.ProjectDailyMetricsRepository;
import com.devforge.ai.common.events.EventEnvelope;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Applies one event to the day bucket it belongs to.
 *
 * <p>Separate from the listener so the counting logic is testable without a broker, and because the
 * listener's job — parse, deduplicate, acknowledge — is a different concern from deciding what an
 * event means.
 *
 * <p>Deliberately not {@code @Transactional}: it runs inside the transaction
 * {@code IdempotentEventProcessor} opens, so the counter increment and the processed-event marker
 * commit together. That pairing is what makes the counts trustworthy — a crash between them would
 * otherwise either double-count or lose an event, and neither is detectable afterwards.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricsRecorder {

  /** Task event names, duplicated as constants rather than importing task-service's jar. */
  static final String TASK_CREATED = "TaskCreated";
  static final String TASK_COMPLETED = "TaskCompleted";
  static final String TASK_ASSIGNED = "TaskAssigned";
  static final String REPOSITORY_CREATED = "RepositoryCreated";
  static final String REPOSITORY_PUSHED = "RepositoryPushed";

  private final ProjectDailyMetricsRepository metrics;

  /** Bounded, so one malformed event cannot add a billion commits to a chart. */
  static int commitCount(Map<String, Object> payload) {
    return payload.get("commitCount") instanceof Number n && n.intValue() >= 1
        ? Math.min(n.intValue(), 10_000)
        : 1;
  }

  public void record(EventEnvelope<Map<String, Object>> envelope) {
    var payload = envelope.payload() == null ? Map.<String, Object>of() : envelope.payload();

    var projectId = uuid(payload.get("projectId"));
    var organizationId = envelope.tenantId();
    if (projectId == null || organizationId == null) {
      // Nothing to attribute the activity to. Logged rather than thrown: a malformed event is a
      // producer bug, and dead-lettering it would stop every event behind it on that partition
      // for a counter that nobody is waiting on.
      log.warn("Skipping {} ({}): no project or tenant to attribute it to",
          envelope.eventType(), envelope.eventId());
      return;
    }

    Consumer<ProjectDailyMetricsEntity> increment = switch (envelope.eventType()) {
      case TASK_CREATED -> m -> m.setTasksCreated(m.getTasksCreated() + 1);
      case TASK_COMPLETED -> m -> m.setTasksCompleted(m.getTasksCompleted() + 1);
      case TASK_ASSIGNED -> m -> m.setTasksAssigned(m.getTasksAssigned() + 1);
      // A push over git carries how many commits it added; a commit from the UI is one.
      case REPOSITORY_PUSHED -> m -> m.setCommits(m.getCommits() + commitCount(payload));
      case REPOSITORY_CREATED -> m -> m.setRepositoriesCreated(m.getRepositoriesCreated() + 1);
      default -> null;
    };

    if (increment == null) {
      // An event this service does not count. Ignored rather than rejected: a consumer that
      // dead-lettered unknown types would start failing the moment any producer shipped a new one.
      log.debug("No metric for event type {}", envelope.eventType());
      return;
    }

    // The day the event happened, not the day it was consumed.
    var day = envelope.timestamp().atZone(ZoneOffset.UTC).toLocalDate();

    var bucket = metrics.findByProjectIdAndMetricDate(projectId, day)
        .orElseGet(() -> ProjectDailyMetricsEntity.builder()
            .projectId(projectId)
            .organizationId(organizationId)
            .metricDate(day)
            .build());

    increment.accept(bucket);
    metrics.save(bucket);
  }

  private static UUID uuid(Object value) {
    if (value == null) {
      return null;
    }
    try {
      return UUID.fromString(value.toString());
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }
}
