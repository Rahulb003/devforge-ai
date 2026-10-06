package com.devforge.ai.documentationservice.entity;

import com.devforge.ai.documentationservice.model.DocSetStatus;
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

/** One generation run over one repository at one ref. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "doc_sets")
public class DocSetEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "project_id", nullable = false, updatable = false)
  private UUID projectId;

  @Column(name = "organization_id", nullable = false, updatable = false)
  private UUID organizationId;

  @Column(name = "repository_id", nullable = false, updatable = false)
  private UUID repositoryId;

  @Column(name = "ref", nullable = false, length = 255)
  private String ref;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 30)
  private DocSetStatus status;

  @Column(name = "files_scanned", nullable = false)
  private int filesScanned;

  /**
   * Why generation could not finish.
   *
   * <p>A doc set that could not read the code must never look like one that found nothing to say:
   * the second reads as "this repository has no API", which is a conclusion the run never reached.
   */
  @Column(name = "failure_reason", length = 1000)
  private String failureReason;

  @Column(name = "generated_by", nullable = false, updatable = false)
  private UUID generatedBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "completed_at")
  private Instant completedAt;

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
