package com.devforge.ai.reviewservice.entity;

import com.devforge.ai.reviewservice.model.ReviewModel.GateResult;
import com.devforge.ai.reviewservice.model.ReviewModel.ReviewStatus;
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

/** One analysis run over one repository at one ref. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "reviews")
public class ReviewEntity {

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

  /** Null for a whole-tree review; set when only the difference was analysed. */
  @Column(name = "base_ref", length = 255)
  private String baseRef;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 30)
  private ReviewStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "gate", length = 20)
  private GateResult gate;

  @Column(name = "files_analysed", nullable = false)
  private int filesAnalysed;

  /**
   * Counts per severity, denormalised.
   *
   * <p>A list of reviews is the common read, and counting findings per row would be a query per
   * review. They are written once when the review completes and never recomputed.
   */
  @Column(name = "blocker_count", nullable = false)
  private int blockerCount;

  @Column(name = "high_count", nullable = false)
  private int highCount;

  @Column(name = "medium_count", nullable = false)
  private int mediumCount;

  @Column(name = "low_count", nullable = false)
  private int lowCount;

  @Column(name = "failure_reason", length = 1000)
  private String failureReason;

  @Column(name = "requested_by", nullable = false, updatable = false)
  private UUID requestedBy;

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
