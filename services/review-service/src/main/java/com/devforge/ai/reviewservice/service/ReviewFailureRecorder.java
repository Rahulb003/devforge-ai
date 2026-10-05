package com.devforge.ai.reviewservice.service;

import com.devforge.ai.reviewservice.entity.ReviewEntity;
import com.devforge.ai.reviewservice.model.ReviewModel.ReviewStatus;
import com.devforge.ai.reviewservice.repository.ReviewRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records that a review could not run, in a transaction of its own.
 *
 * <p>This exists because the obvious version does not work. Saving a {@code FAILED} row inside
 * {@link ReviewService#run} and then letting the exception propagate rolls the row back with the
 * transaction — so an outage left no trace at all, which is indistinguishable from nobody ever
 * having asked for a review. A test asserting the row exists is what caught it.
 *
 * <p>It is a separate bean rather than a {@code REQUIRES_NEW} method on {@code ReviewService},
 * because Spring's proxying ignores propagation on a self-invocation: calling it from within the
 * same class would silently join the caller's transaction and reintroduce the bug. The same trap
 * cost auth-service a rolled-back session revocation.
 */
@Service
@RequiredArgsConstructor
public class ReviewFailureRecorder {

  private final ReviewRepository reviews;

  /**
   * Writes a {@code FAILED} review and commits it independently of the caller.
   *
   * <p>Deliberately inserts a new row rather than updating one: by the time this is called the
   * caller's transaction is doomed, so any row it created is already unreachable.
   *
   * <p>No gate is set. A failed review must never carry {@code PASS} — "no problems found" when the
   * code could not be read looks exactly like a clean repository, and would be trusted.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordFailure(
      UUID organizationId,
      UUID projectId,
      UUID repositoryId,
      String ref,
      String baseRef,
      UUID requestedBy,
      String reason) {

    reviews.save(ReviewEntity.builder()
        .projectId(projectId)
        .organizationId(organizationId)
        .repositoryId(repositoryId)
        .ref(ref)
        .baseRef(baseRef)
        .status(ReviewStatus.FAILED)
        .failureReason(reason == null ? "Analysis failed" : reason)
        .requestedBy(requestedBy)
        .completedAt(Instant.now())
        .build());
  }
}
