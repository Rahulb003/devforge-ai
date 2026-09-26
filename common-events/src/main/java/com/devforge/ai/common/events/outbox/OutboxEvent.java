package com.devforge.ai.common.events.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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
 * A domain event staged for publication, written in the same transaction as the state change it
 * describes.
 *
 * <p>This exists to close the gap between "the database committed" and "Kafka accepted the
 * message". Publishing inline means either the event is lost when the broker is briefly
 * unavailable after a successful commit, or a rollback leaves an event announcing a change that
 * never happened. Writing the event to this table transactionally makes the two atomic, and a
 * separate publisher drains it afterwards.
 *
 * <p>The guarantee is therefore <em>at-least-once</em>: a crash between a successful send and the
 * row being marked published will resend. Consumers must be idempotent; see
 * {@code ProcessedEvent}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "outbox_events", indexes = {
    // The publisher polls for unpublished rows in insertion order.
    @Index(name = "idx_outbox_unpublished", columnList = "published_at, created_at")
})
public class OutboxEvent {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  /** Mirrors the envelope's eventId, so a consumer's deduplication key is traceable back here. */
  @Column(name = "event_id", nullable = false, unique = true, updatable = false)
  private UUID eventId;

  @Column(name = "event_type", nullable = false, length = 100)
  private String eventType;

  @Column(name = "topic", nullable = false, length = 150)
  private String topic;

  @Column(name = "partition_key", length = 200)
  private String partitionKey;

  /** The serialised {@code EventEnvelope}, stored whole so the publisher does no re-assembly. */
  @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
  private String payload;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /** Null until the broker has acknowledged the send. */
  @Column(name = "published_at")
  private Instant publishedAt;

  @Column(name = "attempts", nullable = false)
  private int attempts;

  /** Last failure, kept for diagnosis. Truncated: it must not grow without bound. */
  @Column(name = "last_error", length = 1000)
  private String lastError;

  public boolean isPublished() {
    return publishedAt != null;
  }

  @PrePersist
  protected void onCreate() {
    if (id == null) {
      id = UUID.randomUUID();
    }
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }
}
