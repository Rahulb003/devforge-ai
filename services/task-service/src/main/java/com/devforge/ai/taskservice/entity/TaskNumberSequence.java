package com.devforge.ai.taskservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Allocates the per-project task number.
 *
 * <p>A dedicated row per project, claimed under a pessimistic lock. Deriving the next number from
 * MAX(task_number) would let two concurrent creates read the same value and race, and the unique
 * constraint on (project_id, task_number) would then reject one of them outright — a user-visible
 * failure caused purely by timing.
 *
 * <p>Not a BaseEntity: it has no lifecycle of its own, only a counter.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "task_number_sequences")
public class TaskNumberSequence {

  @Id
  @Column(name = "project_id", nullable = false, updatable = false)
  private UUID projectId;

  @Column(name = "next_number", nullable = false)
  private Integer nextNumber;
}
