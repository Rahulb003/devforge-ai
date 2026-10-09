package com.devforge.ai.gitservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A proposal to merge one branch into another, and its outcome.
 *
 * <p>Nothing about what the pull request changes is stored: the diff and whether it can merge are
 * computed from the branches at read time, so the record can never disagree with the repository.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "pull_requests")
public class PullRequestEntity {

  public enum Status { OPEN, MERGED, CLOSED }

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "repository_id", nullable = false, updatable = false)
  private UUID repositoryId;

  @Column(name = "number", nullable = false, updatable = false)
  private int number;

  @Column(name = "title", nullable = false, length = 200)
  private String title;

  @Column(name = "description", length = 10000)
  private String description;

  @Column(name = "source_branch", nullable = false, updatable = false, length = 255)
  private String sourceBranch;

  @Column(name = "target_branch", nullable = false, updatable = false, length = 255)
  private String targetBranch;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private Status status;

  @Column(name = "author_id", nullable = false, updatable = false)
  private UUID authorId;

  @Column(name = "merged_by")
  private UUID mergedBy;

  @Column(name = "merge_commit_id", length = 40)
  private String mergeCommitId;

  @Column(name = "closed_by")
  private UUID closedBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at")
  private Instant updatedAt;

  @Column(name = "closed_at")
  private Instant closedAt;

  // Two people merging and closing the same pull request at once: one wins, the other gets 409.
  @Version
  @Column(name = "version", nullable = false)
  private Long version;
}
