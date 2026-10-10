package com.devforge.ai.analyticsservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One recorded change. Immutable: there are no setters, and nothing updates or deletes rows. */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "audit_log")
public class AuditEntryEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "event_id", nullable = false, updatable = false, unique = true)
  private UUID eventId;

  @Column(name = "event_type", nullable = false, updatable = false, length = 100)
  private String eventType;

  @Column(name = "source", updatable = false, length = 100)
  private String source;

  @Column(name = "organization_id", updatable = false)
  private UUID organizationId;

  @Column(name = "project_id", updatable = false)
  private UUID projectId;

  @Column(name = "actor_id", updatable = false)
  private UUID actorId;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  @Column(name = "details", nullable = false, updatable = false, columnDefinition = "TEXT")
  private String details;

  // The chain position (see AuditChain). Null only on rows written before the chain existed.
  @Column(name = "chain_key", updatable = false, length = 80)
  private String chainKey;

  @Column(name = "chain_sequence", updatable = false)
  private Long chainSequence;

  @Column(name = "previous_hash", updatable = false, length = 64)
  private String previousHash;

  @Column(name = "entry_hash", updatable = false, length = 64)
  private String entryHash;

  /** Sets the chain position once, before the row is first saved. */
  public void chain(long sequence, String previousHash) {
    this.chainSequence = sequence;
    this.previousHash = previousHash;
    this.entryHash = AuditChain.hash(previousHash, sequence, this);
  }
}
