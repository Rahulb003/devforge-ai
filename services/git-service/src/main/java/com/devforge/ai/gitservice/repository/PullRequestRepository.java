package com.devforge.ai.gitservice.repository;

import com.devforge.ai.gitservice.entity.PullRequestEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PullRequestRepository extends JpaRepository<PullRequestEntity, UUID> {

  Optional<PullRequestEntity> findByRepositoryIdAndNumber(UUID repositoryId, int number);

  List<PullRequestEntity> findByRepositoryIdOrderByNumberDesc(UUID repositoryId);

  List<PullRequestEntity> findByRepositoryIdAndStatusOrderByNumberDesc(
      UUID repositoryId, PullRequestEntity.Status status);

  boolean existsByRepositoryIdAndSourceBranchAndTargetBranchAndStatus(
      UUID repositoryId, String sourceBranch, String targetBranch, PullRequestEntity.Status status);

  @Query("select coalesce(max(p.number), 0) from PullRequestEntity p where p.repositoryId = :repositoryId")
  int maxNumber(UUID repositoryId);
}
