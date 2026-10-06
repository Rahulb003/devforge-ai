package com.devforge.ai.documentationservice.service;

import com.devforge.ai.documentationservice.entity.DocSetEntity;
import com.devforge.ai.documentationservice.model.DocSetStatus;
import com.devforge.ai.documentationservice.repository.DocSetRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records that generation could not run, in a transaction of its own.
 *
 * <p>A separate bean rather than a {@code REQUIRES_NEW} method on {@link DocumentationService},
 * because Spring's proxying ignores propagation on a self-invocation: calling it from within the
 * same class would silently join the caller's doomed transaction and the row would roll back with
 * it. review-service learned this the same way, and auth-service before that.
 */
@Service
@RequiredArgsConstructor
public class DocSetFailureRecorder {

  private final DocSetRepository docSets;

  /**
   * Writes a {@code FAILED} set and commits it independently of the caller.
   *
   * <p>Inserts a new row rather than updating one: by the time this runs the caller's transaction is
   * doomed, so any row it created is already unreachable.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordFailure(
      UUID organizationId,
      UUID projectId,
      UUID repositoryId,
      String ref,
      UUID generatedBy,
      String reason) {

    docSets.save(DocSetEntity.builder()
        .projectId(projectId)
        .organizationId(organizationId)
        .repositoryId(repositoryId)
        .ref(ref)
        .status(DocSetStatus.FAILED)
        .failureReason(reason == null ? "Generation failed" : reason)
        .generatedBy(generatedBy)
        .completedAt(Instant.now())
        .build());
  }
}
