package com.devforge.ai.deploymentservice.execution;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionRepository extends JpaRepository<ExecutionEntity, UUID> {

  List<ExecutionEntity> findByProjectIdOrderByCreatedAtDesc(UUID projectId, Pageable page);

  Optional<ExecutionEntity> findByIdAndProjectId(UUID id, UUID projectId);

  long countByUserIdAndCreatedAtAfterAndCreatedAtLessThanEqual(UUID userId, Instant since, Instant upTo);
}
