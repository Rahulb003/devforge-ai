package com.devforge.ai.gitservice.repository;

import com.devforge.ai.gitservice.entity.PullRequestCommentEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PullRequestCommentRepository extends JpaRepository<PullRequestCommentEntity, UUID> {

  List<PullRequestCommentEntity> findByPullRequestIdOrderByCreatedAtAsc(UUID pullRequestId);

  /** Scoped to the pull request, so a comment id from elsewhere resolves to nothing. */
  Optional<PullRequestCommentEntity> findByIdAndPullRequestId(UUID id, UUID pullRequestId);
}
