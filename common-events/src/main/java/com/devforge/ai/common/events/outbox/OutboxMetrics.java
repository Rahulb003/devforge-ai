package com.devforge.ai.common.events.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Exports the outbox's backlog to Prometheus.
 *
 * <p>An outbox that stops draining is the quietest failure this system has: every request still
 * succeeds, the state changes are committed, and the only symptom is that notifications, the audit
 * log and analytics stop moving. The publisher logs a stuck row at error level, but nothing alerts
 * on a log line, so the counts are gauges the alert rules can watch.
 *
 * <p>Each scrape runs two counts against the outbox table. It holds only unpublished rows and the
 * last week of published ones, so that stays cheap at a 15-second scrape interval.
 */
@Component
@ConditionalOnProperty(name = "devforge.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxMetrics implements MeterBinder {

  private final OutboxEventRepository outboxEventRepository;

  @Value("${devforge.outbox.alert-after-attempts:10}")
  private int alertAfterAttempts;

  public OutboxMetrics(OutboxEventRepository outboxEventRepository) {
    this.outboxEventRepository = outboxEventRepository;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder("devforge.outbox.pending", outboxEventRepository,
            OutboxEventRepository::countByPublishedAtIsNull)
        .description("Outbox events not yet acknowledged by the broker")
        .register(registry);
    Gauge.builder("devforge.outbox.stuck", outboxEventRepository,
            repository -> repository.countByPublishedAtIsNullAndAttemptsGreaterThanEqual(
                alertAfterAttempts))
        .description("Outbox events that have failed often enough not to drain on their own")
        .register(registry);
  }
}
