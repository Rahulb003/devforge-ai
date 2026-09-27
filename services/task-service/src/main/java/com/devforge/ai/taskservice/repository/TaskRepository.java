package com.devforge.ai.taskservice.repository;

import com.devforge.ai.taskservice.entity.TaskEntity;
import com.devforge.ai.taskservice.model.TaskStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TaskRepository extends JpaRepository<TaskEntity, UUID> {

  /**
   * Fetches a task only within its project.
   *
   * <p>Looking a task up by id alone and checking the project afterwards is the IDOR shape: one
   * forgotten check away from cross-project access. Scoping the query means a mismatched project
   * simply finds nothing.
   */
  Optional<TaskEntity> findByIdAndProjectId(UUID id, UUID projectId);

  Page<TaskEntity> findByProjectId(UUID projectId, Pageable pageable);

  List<TaskEntity> findByProjectIdAndStatusOrderByBoardPositionAsc(UUID projectId, TaskStatus status);

  List<TaskEntity> findByProjectIdOrderByBoardPositionAsc(UUID projectId);

  List<TaskEntity> findByParentTaskId(UUID parentTaskId);

  List<TaskEntity> findBySprintId(UUID sprintId);

  long countByProjectIdAndStatus(UUID projectId, TaskStatus status);

  /** Highest position currently used in a column, for appending to the end. */
  @Query("SELECT COALESCE(MAX(t.boardPosition), 0) FROM TaskEntity t "
      + "WHERE t.projectId = :projectId AND t.status = :status")
  int findMaxBoardPosition(@Param("projectId") UUID projectId, @Param("status") TaskStatus status);
}
