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

/** A comment on a pull request. Immutable once written; its author may delete it. */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "pull_request_comments")
public class PullRequestCommentEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "pull_request_id", nullable = false, updatable = false)
  private UUID pullRequestId;

  @Column(name = "author_id", nullable = false, updatable = false)
  private UUID authorId;

  @Column(name = "author_name", nullable = false, updatable = false, length = 100)
  private String authorName;

  @Column(name = "body", nullable = false, updatable = false, length = 10000)
  private String body;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;
}
