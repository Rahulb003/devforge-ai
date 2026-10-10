package com.devforge.ai.common.events.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drains the outbox to Kafka.
 *
 * <p>Runs on a poll rather than reacting to each commit, because the poll is what makes the design
 * crash-safe: anything staged but unsent is simply picked up on the next tick, including after the
 * process died mid-publish or the broker was down for an hour.
 *
 * <p>Delivery is at-least-once. A crash between a successful send and the row being marked
 * published resends the event, so consumers must deduplicate on {@code eventId}; see
 * {@link ProcessedEvent}. The alternative — marking first, sending after — would silently drop
 * events, which is strictly worse than a duplicate a consumer can detect.
 *
 * <p>Disabled by setting {@code devforge.outbox.enabled=false}, which tests and any deployment
 * without a broker use.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "devforge.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

  private final OutboxEventRepository outboxEventRepository;
  private final KafkaTemplate<String, String> kafkaTemplate;

  @Value("${devforge.outbox.batch-size:100}")
  private int batchSize;

  @Value("${devforge.outbox.send-timeout-seconds:10}")
  private int sendTimeoutSeconds;

  /** Attempts after which a row is considered stuck and reported rather than retried quietly. */
  @Value("${devforge.outbox.alert-after-attempts:10}")
  private int alertAfterAttempts;

  /**
   * Whether the database supports {@code FOR UPDATE SKIP LOCKED}.
   *
   * <p>True for PostgreSQL. H2 in tests does not support it, and without the skip-locked claim
   * multiple publishers would duplicate every event, so this must stay true in production.
   */
  @Value("${devforge.outbox.use-skip-locked:true}")
  private boolean useSkipLocked;

  /**
   * Opens the drain's transaction explicitly rather than through {@code @Transactional}.
   *
   * <p>The scheduled {@link #publishPending} calls {@link #drainOnce} on {@code this}, which never
   * passes through Spring's proxy, so an annotation there was silently ignored. The claim's
   * FOR UPDATE SKIP LOCKED then ran in the repository's own transaction, its row locks were
   * released as soon as the query returned, and publishing and marking happened unprotected: with
   * two instances of a service, 66 of 200 events were published twice. Found by CI running two
   * task-services against one outbox; every test had called drainOnce through the proxy.
   */
  private final TransactionTemplate transactionTemplate;

  public OutboxPublisher(
      OutboxEventRepository outboxEventRepository, KafkaTemplate<String, String> kafkaTemplate,
      PlatformTransactionManager transactionManager) {
    this.outboxEventRepository = outboxEventRepository;
    this.kafkaTemplate = kafkaTemplate;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  @Scheduled(fixedDelayString = "${devforge.outbox.poll-interval-ms:1000}")
  public void publishPending() {
    try {
      var published = drainOnce();
      if (published > 0) {
        log.debug("Published {} outbox event(s)", published);
      }
    } catch (RuntimeException ex) {
      // A scheduled method that throws stops being rescheduled in some configurations, and a
      // publisher that silently dies is an outage that looks like "events stopped arriving".
      log.error("Outbox drain failed; will retry on the next tick", ex);
    }
  }

  /**
   * Publishes one batch.
   *
   * <p>The claim, the sends and the marking all happen in one transaction, so the rows stay locked
   * against every other publisher until they are marked published.
   *
   * @return how many events were acknowledged by the broker.
   */
  public int drainOnce() {
    Integer published = transactionTemplate.execute(status -> drainInTransaction());
    return published == null ? 0 : published;
  }

  private int drainInTransaction() {
    List<OutboxEvent> batch = useSkipLocked
        ? outboxEventRepository.claimUnpublished(batchSize)
        : outboxEventRepository.findByPublishedAtIsNullOrderByCreatedAtAsc(
            PageRequest.of(0, batchSize));

    if (batch.isEmpty()) {
      return 0;
    }

    int published = 0;
    for (var event : batch) {
      event.setAttempts(event.getAttempts() + 1);
      try {
        // Blocking on the ack is intentional: the row must only be marked published once the
        // broker has durably accepted it, or a failed send would look like a success.
        kafkaTemplate
            .send(event.getTopic(), event.getPartitionKey(), event.getPayload())
            .get(sendTimeoutSeconds, TimeUnit.SECONDS);

        event.setPublishedAt(Instant.now());
        event.setLastError(null);
        published++;
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        recordFailure(event, "Interrupted while publishing");
        break;
      } catch (Exception ex) {
        // Left unpublished so the next tick retries it. Ordering within a tenant is preserved
        // because the next poll re-reads from the oldest unpublished row.
        recordFailure(event, ex.getMessage());
      }
      outboxEventRepository.save(event);
    }
    return published;
  }

  private void recordFailure(OutboxEvent event, String message) {
    var truncated = message == null ? "unknown error"
        : message.substring(0, Math.min(message.length(), 1000));
    event.setLastError(truncated);

    if (event.getAttempts() >= alertAfterAttempts) {
      // Past this point it is not transient. Surfaced at error level so monitoring can alert;
      // a row retried forever at debug level is an invisible data-loss incident.
      log.error("Outbox event {} ({}) has failed {} times and is not draining: {}",
          event.getEventId(), event.getEventType(), event.getAttempts(), truncated);
    } else {
      log.warn("Outbox event {} failed (attempt {}): {}",
          event.getEventId(), event.getAttempts(), truncated);
    }
  }

  /** Removes rows already published, keeping the table small. */
  @Scheduled(fixedDelayString = "${devforge.outbox.cleanup-interval-ms:3600000}")
  public void cleanupPublished() {
    var retention = Duration.ofDays(7);
    // Explicit, like the drain: whether the scheduler calls through the proxy is not something to
    // rely on for a bulk delete.
    Integer removed = transactionTemplate.execute(
        status -> outboxEventRepository.deletePublishedBefore(Instant.now().minus(retention)));
    if (removed != null && removed > 0) {
      log.info("Pruned {} published outbox row(s) older than {}", removed, retention);
    }
  }

  /** Number of events waiting to be published; exported as a health/metric signal. */
  public long pendingCount() {
    return outboxEventRepository.countByPublishedAtIsNull();
  }
}
