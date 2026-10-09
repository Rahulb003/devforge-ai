package com.devforge.ai.gitservice.repository;

import com.devforge.ai.gitservice.entity.PullRequestApprovalEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PullRequestApprovalRepository extends JpaRepository<PullRequestApprovalEntity, UUID> {

  List<PullRequestApprovalEntity> findByPullRequestIdOrderByCreatedAtAsc(UUID pullRequestId);

  Optional<PullRequestApprovalEntity> findByPullRequestIdAndUserId(UUID pullRequestId, UUID userId);
}
