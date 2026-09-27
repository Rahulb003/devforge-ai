package com.devforge.ai.taskservice.repository;

import com.devforge.ai.taskservice.entity.TaskLabelEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TaskLabelRepository extends JpaRepository<TaskLabelEntity, UUID> {

  List<TaskLabelEntity> findByTaskId(UUID taskId);

  boolean existsByTaskIdAndLabelIgnoreCase(UUID taskId, String label);

  void deleteByTaskId(UUID taskId);
}
