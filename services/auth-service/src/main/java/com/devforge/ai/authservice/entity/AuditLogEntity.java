package com.devforge.ai.authservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "audit_logs")
public class AuditLogEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id")
  private UserEntity user;

  @Column(name = "action", nullable = false, length = 100)
  private String action;

  @Column(name = "entity_type", length = 100)
  private String entityType;

  @Column(name = "entity_id", length = 100)
  private String entityId;

  @Column(name = "ip_address", length = 45)
  private String ipAddress;

  @Column(name = "details", columnDefinition = "TEXT")
  private String details;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /** Position in the account audit chain; null on rows from before the chain existed. */
  @Column(name = "chain_sequence", updatable = false)
  private Long chainSequence;

  @Column(name = "previous_hash", updatable = false, length = 64)
  private String previousHash;

  @Column(name = "entry_hash", updatable = false, length = 64)
  private String entryHash;

  @PrePersist
  protected void onCreate() {
    if (id == null) id = UUID.randomUUID();
    if (createdAt == null) createdAt = Instant.now();
  }
}
