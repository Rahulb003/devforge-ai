package com.devforge.ai.deploymentservice.pipeline;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface PipelineRunRepository extends JpaRepository<PipelineRunEntity, UUID> {

  List<PipelineRunEntity> findByRepositoryIdAndProjectIdOrderByCreatedAtDesc(
      UUID repositoryId, UUID projectId, Pageable page);

  Optional<PipelineRunEntity> findByIdAndRepositoryIdAndProjectId(
      UUID id, UUID repositoryId, UUID projectId);

  long countByTriggeredByAndCreatedAtAfterAndCreatedAtLessThanEqual(UUID userId, Instant since,
      Instant upTo);

  /**
   * Runs still open long after any run could have finished: their instance stopped. Marked rather
   * than left RUNNING for ever, which a reader would take to mean "still going".
   */
  @Modifying
  @Transactional
  @Query("update PipelineRunEntity r set r.status = com.devforge.ai.deploymentservice.pipeline"
      + ".PipelineRunEntity$Status.INTERRUPTED, r.finishedAt = :now,"
      + " r.message = 'The run stopped part-way: the instance running it shut down'"
      + " where r.status in (com.devforge.ai.deploymentservice.pipeline.PipelineRunEntity$Status.QUEUED,"
      + " com.devforge.ai.deploymentservice.pipeline.PipelineRunEntity$Status.RUNNING)"
      + " and r.createdAt < :before")
  int interruptStale(Instant before, Instant now);
}
