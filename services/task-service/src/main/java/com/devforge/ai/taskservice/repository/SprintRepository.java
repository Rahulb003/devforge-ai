package com.devforge.ai.taskservice.repository;

import com.devforge.ai.taskservice.entity.SprintEntity;
import com.devforge.ai.taskservice.model.SprintStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SprintRepository extends JpaRepository<SprintEntity, UUID> {

  Optional<SprintEntity> findByIdAndProjectId(UUID id, UUID projectId);

  /**
   * Newest first, with the id as a tiebreak.
   *
   * <p>Created-at alone is not a stable sort: two sprints created in the same millisecond have no
   * defined order, so the list could reshuffle between refreshes and a client acting on "the first
   * sprint" would act on a different row each time. That showed up as an intermittently failing
   * browser test before it showed up as a user complaint.
   */
  List<SprintEntity> findByProjectIdOrderByCreatedAtDescIdAsc(UUID projectId);

  /** A project may have at most one active sprint; used to enforce that. */
  Optional<SprintEntity> findByProjectIdAndStatus(UUID projectId, SprintStatus status);
}
