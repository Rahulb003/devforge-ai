package com.devforge.ai.gitservice.entity;

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
import lombok.Setter;

/**
 * One reviewer's approval of a pull request, pinned to the source commit they approved.
 *
 * <p>Approving again after new commits moves the pin forward; it is never moved for them.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "pull_request_approvals")
public class PullRequestApprovalEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "pull_request_id", nullable = false, updatable = false)
  private UUID pullRequestId;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "user_name", nullable = false, length = 100)
  private String userName;

  @Column(name = "commit_id", nullable = false, length = 40)
  private String commitId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;
}
