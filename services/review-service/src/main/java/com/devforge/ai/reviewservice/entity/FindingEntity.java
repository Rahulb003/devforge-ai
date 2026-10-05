package com.devforge.ai.reviewservice.entity;

import com.devforge.ai.reviewservice.model.ReviewModel.FindingCategory;
import com.devforge.ai.reviewservice.model.ReviewModel.Severity;
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

/** One thing a rule noticed, as stored. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "review_findings")
public class FindingEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  /**
   * Plain column rather than a {@code @ManyToOne}.
   *
   * <p>A finding is only ever read through its review, so the association would buy nothing and
   * would make writing a few hundred findings a few hundred managed entities.
   */
  @Column(name = "review_id", nullable = false, updatable = false)
  private UUID reviewId;

  @Column(name = "rule_id", nullable = false, length = 100)
  private String ruleId;

  @Enumerated(EnumType.STRING)
  @Column(name = "severity", nullable = false, length = 20)
  private Severity severity;

  @Enumerated(EnumType.STRING)
  @Column(name = "category", nullable = false, length = 40)
  private FindingCategory category;

  @Column(name = "file_path", nullable = false, length = 1024)
  private String filePath;

  /** 1-based. Null when the finding concerns the file as a whole. */
  @Column(name = "line_number")
  private Integer lineNumber;

  @Column(name = "message", nullable = false, length = 1000)
  private String message;

  /**
   * The offending line, already redacted where it held a credential.
   *
   * <p>See {@code SecretRules}: a finding that quoted the secret would copy it into this table, the
   * API response and the logs.
   */
  @Column(name = "snippet", length = 500)
  private String snippet;

  @Column(name = "dismissed_at")
  private Instant dismissedAt;

  @Column(name = "dismissed_by")
  private UUID dismissedBy;

  /**
   * Why it was dismissed. Required when dismissing.
   *
   * <p>A dismissal with no reason is indistinguishable from someone clearing the list to make the
   * gate go green, and the record exists precisely so that is visible afterwards.
   */
  @Column(name = "dismiss_reason", length = 500)
  private String dismissReason;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public boolean isDismissed() {
    return dismissedAt != null;
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
