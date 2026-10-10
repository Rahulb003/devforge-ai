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

  /**
   * Creates the project's counter row unless it exists.
   *
   * <p>The lock above only works once the row is there. For a new project it is not, so concurrent
   * first creates each inserted one and all but one failed on the primary key. Inserting with
   * ON CONFLICT DO NOTHING first means they all converge on the same row, and the lock then
   * serialises them; a later insert waits for the first one's transaction rather than failing.
   */
  @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
  @org.springframework.data.jpa.repository.Query(nativeQuery = true, value = """
      INSERT INTO task_number_sequences (project_id, next_number) VALUES (:projectId, 1)
      ON CONFLICT DO NOTHING
      """)
  void createIfAbsent(@org.springframework.data.repository.query.Param("projectId") UUID projectId);
}
