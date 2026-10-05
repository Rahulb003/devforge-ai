package com.devforge.ai.reviewservice.repository;

import com.devforge.ai.reviewservice.entity.FindingEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Findings, always scoped by review.
 *
 * <p>Scoping by review is what makes the project check transitive: the review was already loaded
 * with its project id, so a finding id from elsewhere cannot resolve.
 */
public interface FindingRepository extends JpaRepository<FindingEntity, UUID> {

  /**
   * Worst first, then by location.
   *
   * <p>Severity is an enum ordered BLOCKER..INFO, so ascending is most-severe-first — which is the
   * order anyone reading a review wants, and it means truncation in a UI drops the least important.
   */
  List<FindingEntity> findByReviewIdOrderBySeverityAscFilePathAscLineNumberAsc(UUID reviewId);

  Optional<FindingEntity> findByIdAndReviewId(UUID id, UUID reviewId);

  long countByReviewIdAndDismissedAtIsNull(UUID reviewId);
}
