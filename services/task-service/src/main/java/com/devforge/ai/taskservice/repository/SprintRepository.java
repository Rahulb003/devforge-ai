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

  List<SprintEntity> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

  /** A project may have at most one active sprint; used to enforce that. */
  Optional<SprintEntity> findByProjectIdAndStatus(UUID projectId, SprintStatus status);
}
