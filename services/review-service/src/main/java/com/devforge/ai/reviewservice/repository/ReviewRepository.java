package com.devforge.ai.reviewservice.repository;

import com.devforge.ai.reviewservice.entity.ReviewEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reviews, always scoped by project.
 *
 * <p>A top-level interface, not a nested one: Spring Data does not create repository beans for
 * interfaces nested inside another type, and the failure is an unsatisfied dependency at startup
 * rather than anything that points at the cause.
 *
 * <p>Lookups take the project id as well as the review id. {@code findById} alone would happily
 * return a review from another tenant, leaving every caller responsible for remembering the check.
 */
public interface ReviewRepository extends JpaRepository<ReviewEntity, UUID> {

  Page<ReviewEntity> findByRepositoryIdAndProjectIdOrderByCreatedAtDescIdAsc(
      UUID repositoryId, UUID projectId, Pageable pageable);

  Optional<ReviewEntity> findByIdAndProjectId(UUID id, UUID projectId);

  /** The newest review for a repository, which is what a status badge shows. */
  Optional<ReviewEntity> findFirstByRepositoryIdAndProjectIdOrderByCreatedAtDescIdAsc(
      UUID repositoryId, UUID projectId);
}
