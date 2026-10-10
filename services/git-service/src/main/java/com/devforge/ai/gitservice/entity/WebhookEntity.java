package com.devforge.ai.gitservice.entity;

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

/** A URL told about every change to a repository's branches. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "repository_webhooks")
public class WebhookEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "repository_id", nullable = false, updatable = false)
  private UUID repositoryId;

  @Column(name = "url", nullable = false, length = 2000)
  private String url;

  @Column(name = "secret", nullable = false, updatable = false, length = 100)
  private String secret;

  @Column(name = "created_by", nullable = false, updatable = false)
  private UUID createdBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "last_status")
  private Integer lastStatus;

  @Column(name = "last_error", length = 500)
  private String lastError;

  @Column(name = "last_delivered_at")
  private Instant lastDeliveredAt;

  @PrePersist
  void onCreate() {
    if (id == null) id = UUID.randomUUID();
    if (createdAt == null) createdAt = Instant.now();
  }
}
