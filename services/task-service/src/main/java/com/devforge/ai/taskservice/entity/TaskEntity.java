package com.devforge.ai.taskservice.entity;

import com.devforge.ai.common.model.BaseEntity;
import com.devforge.ai.taskservice.model.TaskPriority;
import com.devforge.ai.taskservice.model.TaskStatus;
import com.devforge.ai.taskservice.model.TaskType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * A unit of work inside a project.
 *
 * <p>{@code projectId} and {@code organizationId} are plain UUIDs rather than foreign keys:
 * projects live in project-service's database, and a service must not reach into another's schema.
 * {@code organizationId} is stored alongside so a tenant check never needs a cross-service call
 * just to establish which tenant a row belongs to.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "tasks")
public class TaskEntity extends BaseEntity {

  @Column(name = "project_id", nullable = false)
  private UUID projectId;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  /** Sequence within the project, rendered as e.g. CORE-14. */
  @Column(name = "task_number", nullable = false, updatable = false)
  private Integer taskNumber;

  @Column(name = "title", nullable = false, length = 300)
  private String title;

  @Column(name = "description", length = 10000)
  private String description;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 50)
  private TaskStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "priority", nullable = false, length = 50)
  private TaskPriority priority;

  @Enumerated(EnumType.STRING)
  @Column(name = "type", nullable = false, length = 50)
  private TaskType type;

  @Column(name = "assignee_id")
  private UUID assigneeId;

  @Column(name = "reporter_id", nullable = false)
  private UUID reporterId;

  @Column(name = "story_points")
  private Integer storyPoints;

  @Column(name = "due_date")
  private LocalDate dueDate;

  /** Set when the task is a subtask. Self-referencing within this table. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "parent_task_id")
  private TaskEntity parentTask;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "sprint_id")
  private SprintEntity sprint;

  /**
   * Ordering within its Kanban column.
   *
   * <p>Sparse by convention (see TaskService): leaving gaps means a card can be dropped between
   * two others by writing one row, instead of renumbering the entire column on every drag.
   */
  @Column(name = "board_position", nullable = false)
  private Integer boardPosition;

  /** When the task first reached a terminal status. Used for cycle-time reporting. */
  @Column(name = "completed_at")
  private Instant completedAt;
}
