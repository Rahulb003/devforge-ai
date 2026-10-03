package com.devforge.ai.notificationservice.entity;

import com.devforge.ai.notificationservice.model.NotificationCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * One notification for one person.
 *
 * <p>There is deliberately no shared "notification" row with a list of recipients. Read state is
 * per-person, so a shared row would need a join table that is mutated by everyone who reads it,
 * and authorization would stop being a single column comparison. A fan-out event writes one row
 * each instead.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "notifications")
public class NotificationEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  /** The only authorization check this service needs. Always compared against the token subject. */
  @Column(name = "recipient_id", nullable = false, updatable = false)
  private UUID recipientId;

  /** Null for platform-level notifications, such as a security alert about the account itself. */
  @Column(name = "organization_id")
  private UUID organizationId;

  @Enumerated(EnumType.STRING)
  @Column(name = "category", nullable = false, length = 40)
  private NotificationCategory category;

  /** The domain event type that caused this, e.g. {@code TaskAssigned}. */
  @Column(name = "event_type", nullable = false, length = 100)
  private String eventType;

  @Column(name = "title", nullable = false, length = 200)
  private String title;

  @Column(name = "body", length = 1000)
  private String body;

  /** Relative path, so the frontend owns routing and a stored link cannot point off-origin. */
  @Column(name = "link", length = 500)
  private String link;

  @Column(name = "source_event_id")
  private UUID sourceEventId;

  @Column(name = "correlation_id", length = 64)
  private String correlationId;

  @Column(name = "read_at")
  private Instant readAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public boolean isRead() {
    return readAt != null;
  }

  @PrePersist
  void onCreate() {
    if (id == null) {
      id = UUID.randomUUID();
    }
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }
}
