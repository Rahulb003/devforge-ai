package com.devforge.ai.taskservice.repository;

import com.devforge.ai.taskservice.entity.TaskCommentEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TaskCommentRepository extends JpaRepository<TaskCommentEntity, UUID> {

  List<TaskCommentEntity> findByTaskIdOrderByCreatedAtAsc(UUID taskId);

  Optional<TaskCommentEntity> findByIdAndTaskId(UUID id, UUID taskId);

  void deleteByTaskId(UUID taskId);
}
