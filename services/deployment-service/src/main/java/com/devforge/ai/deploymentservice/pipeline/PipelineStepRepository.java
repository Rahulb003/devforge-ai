package com.devforge.ai.deploymentservice.pipeline;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PipelineStepRepository extends JpaRepository<PipelineStepEntity, UUID> {

  List<PipelineStepEntity> findByRunIdOrderByPosition(UUID runId);
}
