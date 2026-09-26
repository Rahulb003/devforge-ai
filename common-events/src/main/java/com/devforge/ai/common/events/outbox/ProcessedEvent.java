package com.devforge.ai.common.events.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Record that this consumer has already handled an event.
 *
 * <p>The outbox gives at-least-once delivery, and Kafka itself redelivers on rebalance or a failed
 * offset commit, so a consumer will see the same event twice in normal operation. Handlers that
 * are not naturally idempotent — sending an email, incrementing a counter, creating a row — must
 * check here first, or duplicates become user-visible.
 *
 * <p>The primary key is composite on purpose: two different consumer groups must each be able to
 * process the same event once.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "processed_events")
@jakarta.persistence.IdClass(ProcessedEvent.ProcessedEventId.class)
public class ProcessedEvent {

  @Id
  @Column(name = "event_id", nullable = false, updatable = false)
  private UUID eventId;

  @Id
  @Column(name = "consumer_group", nullable = false, updatable = false, length = 150)
  private String consumerGroup;

  @Column(name = "event_type", nullable = false, length = 100)
  private String eventType;

  @Column(name = "processed_at", nullable = false, updatable = false)
  private Instant processedAt;

  @PrePersist
  protected void onCreate() {
    if (processedAt == null) {
      processedAt = Instant.now();
    }
  }

  /** Composite key: one row per (event, consumer group). */
  public record ProcessedEventId(UUID eventId, String consumerGroup) implements java.io.Serializable {
    public ProcessedEventId() {
      this(null, null);
    }
  }
}
