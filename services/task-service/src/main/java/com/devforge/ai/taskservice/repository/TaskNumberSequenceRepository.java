package com.devforge.ai.taskservice.repository;

import com.devforge.ai.taskservice.entity.TaskNumberSequence;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

@Repository
public interface TaskNumberSequenceRepository extends JpaRepository<TaskNumberSequence, UUID> {

  /**
   * Claims the counter row for update.
   *
   * <p>The pessimistic lock is the point: two concurrent creates in the same project must not read
   * the same next number. Without it they would both write the same value and the unique
   * constraint would fail one of them for no reason the user could understand.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<TaskNumberSequence> findByProjectId(UUID projectId);
}
