package com.devforge.ai.taskservice.service;

import com.devforge.ai.common.exception.ResourceConflictException;
import com.devforge.ai.common.exception.ResourceNotFoundException;
import com.devforge.ai.common.security.AuthenticatedUser;
import com.devforge.ai.taskservice.dto.AssignTaskRequest;
import com.devforge.ai.taskservice.dto.BoardColumn;
import com.devforge.ai.taskservice.dto.CreateTaskRequest;
import com.devforge.ai.taskservice.dto.MoveTaskRequest;
import com.devforge.ai.taskservice.dto.TaskResponse;
import com.devforge.ai.taskservice.dto.UpdateTaskRequest;
import com.devforge.ai.taskservice.entity.TaskEntity;
import com.devforge.ai.taskservice.events.TaskEventPublisher;
import com.devforge.ai.taskservice.model.TaskPriority;
import com.devforge.ai.taskservice.model.TaskStatus;
import com.devforge.ai.taskservice.model.TaskType;
import com.devforge.ai.taskservice.repository.SprintRepository;
import com.devforge.ai.taskservice.repository.TaskCommentRepository;
import com.devforge.ai.taskservice.repository.TaskLabelRepository;
import com.devforge.ai.taskservice.repository.TaskNumberSequenceRepository;
import com.devforge.ai.taskservice.repository.TaskRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tasks, the Kanban board and everything hanging off a task.
 *
 * <p>Every public method asserts project access first. That check is delegated to project-service,
 * which owns project membership, so this service never decides tenancy for itself — see
 * {@link com.devforge.ai.common.security.client.ProjectAccessClient}.
 *
 * <p>Tasks are then resolved with {@code findByIdAndProjectId}, so a task id from another project
 * does not resolve even if the caller has access to the project in the path. That is the second
 * layer, not the only one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

  private final TaskRepository taskRepository;
  private final TaskLabelRepository taskLabelRepository;
  private final TaskCommentRepository taskCommentRepository;
  private final TaskNumberSequenceRepository sequenceRepository;
  private final SprintRepository sprintRepository;
  private final TaskAccessService access;
  private final TaskEventPublisher events;

  @Transactional
  public TaskResponse create(UUID organizationId, UUID projectId, CreateTaskRequest request) {
    access.requireProjectWrite(organizationId, projectId);
    var user = access.requireCurrentUser();

    TaskEntity parent = null;
    if (request.parentTaskId() != null) {
      parent = taskRepository.findByIdAndProjectId(request.parentTaskId(), projectId)
          .orElseThrow(() -> new ResourceNotFoundException("Parent task not found"));
      // One level of nesting. Arbitrary depth makes ordering, rollup and the board
      // ambiguous, and nothing in the product asks for it.
      if (parent.getParentTask() != null) {
        throw new ResourceConflictException("A subtask cannot have subtasks of its own");
      }
    }

    var sprint = request.sprintId() == null ? null
        : sprintRepository.findByIdAndProjectId(request.sprintId(), projectId)
            .orElseThrow(() -> new ResourceNotFoundException("Sprint not found"));

    var status = TaskStatus.BACKLOG;
    var task = taskRepository.save(TaskEntity.builder()
        .projectId(projectId)
        .organizationId(organizationId)
        .taskNumber(nextTaskNumber(projectId))
        .title(request.title())
        .description(request.description())
        .status(status)
        .priority(request.priority() == null ? TaskPriority.MEDIUM : request.priority())
        .type(request.type() == null ? TaskType.TASK : request.type())
        .assigneeId(request.assigneeId())
        .reporterId(user.id())
        .storyPoints(request.storyPoints())
        .dueDate(request.dueDate())
        .parentTask(parent)
        .sprint(sprint)
        // Appended to the end of its column.
        .boardPosition(taskRepository.findMaxBoardPosition(projectId, status) + 1)
        .build());

    events.taskCreated(task, user.id());
    if (task.getAssigneeId() != null) {
      events.taskAssigned(task, null, user.id());
    }
    return toResponse(task);
  }

  /**
   * Claims the next per-project task number.
   *
   * <p>Under a pessimistic lock, so two concurrent creates cannot read the same value. Deriving it
   * from MAX(task_number) instead would let them race and the unique constraint would reject one
   * of them — a failure caused purely by timing, which a user cannot understand or act on.
   */
  private int nextTaskNumber(UUID projectId) {
    sequenceRepository.createIfAbsent(projectId);
    var sequence = sequenceRepository.findByProjectId(projectId)
        .orElseThrow(() -> new IllegalStateException("The task counter for a project was not created"));

    int number = sequence.getNextNumber();
    sequence.setNextNumber(number + 1);
    sequenceRepository.save(sequence);
    return number;
  }

  @Transactional(readOnly = true)
  public Page<TaskResponse> list(UUID organizationId, UUID projectId, Pageable pageable) {
    access.requireProjectAccess(organizationId, projectId);
    return taskRepository.findByProjectId(projectId, pageable).map(this::toResponse);
  }

  @Transactional(readOnly = true)
  public TaskResponse get(UUID organizationId, UUID projectId, UUID taskId) {
    access.requireProjectAccess(organizationId, projectId);
    return toResponse(loadScoped(projectId, taskId));
  }

  /** The whole board: one entry per status, cards already in order. */
  @Transactional(readOnly = true)
  public List<BoardColumn> board(UUID organizationId, UUID projectId) {
    access.requireProjectAccess(organizationId, projectId);

    // Every status is returned, including empty ones: a board that hides empty
    // columns has nowhere to drop the first card.
    return Arrays.stream(TaskStatus.values())
        .map(status -> new BoardColumn(
            status,
            taskRepository.findByProjectIdAndStatusOrderByBoardPositionAsc(projectId, status)
                .stream()
                .map(this::toResponse)
                .toList()))
        .toList();
  }

  @Transactional
  public TaskResponse update(
      UUID organizationId, UUID projectId, UUID taskId, UpdateTaskRequest request) {
    access.requireProjectWrite(organizationId, projectId);
    var task = loadScoped(projectId, taskId);

    if (request.title() != null) {
      task.setTitle(request.title());
    }
    if (request.description() != null) {
      task.setDescription(request.description());
    }
    if (request.priority() != null) {
      task.setPriority(request.priority());
    }
    if (request.type() != null) {
      task.setType(request.type());
    }
    if (request.storyPoints() != null) {
      task.setStoryPoints(request.storyPoints());
    }
    if (request.dueDate() != null) {
      task.setDueDate(request.dueDate());
    }
    if (request.sprintId() != null) {
      task.setSprint(sprintRepository.findByIdAndProjectId(request.sprintId(), projectId)
          .orElseThrow(() -> new ResourceNotFoundException("Sprint not found")));
    }

    return toResponse(taskRepository.save(task));
  }

  /**
   * Moves a card to a column and a position within it.
   *
   * <p>Both affected columns are renumbered contiguously afterwards. Renumbering costs a few extra
   * writes per drag, but positions cannot drift or collide, which is what happens when gaps are
   * exhausted by repeated inserts into the same place.
   */
  @Transactional
  public TaskResponse move(
      UUID organizationId, UUID projectId, UUID taskId, MoveTaskRequest request) {
    access.requireProjectWrite(organizationId, projectId);
    var task = loadScoped(projectId, taskId);

    var previousStatus = task.getStatus();
    var targetStatus = request.status();

    // Snapshot of the destination, excluding the moving card so it cannot appear twice.
    var destination = new ArrayList<>(
        taskRepository.findByProjectIdAndStatusOrderByBoardPositionAsc(projectId, targetStatus));
    destination.removeIf(candidate -> candidate.getId().equals(task.getId()));

    int index = request.position() == null
        ? destination.size()
        : Math.max(0, Math.min(request.position(), destination.size()));
    destination.add(index, task);

    task.setStatus(targetStatus);
    applyCompletion(task, previousStatus, targetStatus);

    for (int position = 0; position < destination.size(); position++) {
      destination.get(position).setBoardPosition(position);
    }
    taskRepository.saveAll(destination);

    if (previousStatus != targetStatus) {
      // The source column now has a hole in its numbering.
      var source = taskRepository.findByProjectIdAndStatusOrderByBoardPositionAsc(
          projectId, previousStatus);
      for (int position = 0; position < source.size(); position++) {
        source.get(position).setBoardPosition(position);
      }
      taskRepository.saveAll(source);

      var actor = access.requireCurrentUser().id();
      events.taskMoved(task, previousStatus, actor);
      if (targetStatus.isTerminal()) {
        events.taskCompleted(task, actor);
      }
    }

    return toResponse(task);
  }

  /**
   * Records when a task first reached a terminal state, and clears it if reopened.
   *
   * <p>Kept as a stored timestamp rather than derived from an audit trail, because cycle time is
   * read far more often than it is written.
   */
  private void applyCompletion(TaskEntity task, TaskStatus from, TaskStatus to) {
    if (to.isTerminal() && !from.isTerminal()) {
      task.setCompletedAt(Instant.now());
    } else if (!to.isTerminal() && from.isTerminal()) {
      task.setCompletedAt(null);
    }
  }

  @Transactional
  public TaskResponse assign(
      UUID organizationId, UUID projectId, UUID taskId, AssignTaskRequest request) {
    access.requireProjectWrite(organizationId, projectId);
    var task = loadScoped(projectId, taskId);

    var previousAssignee = task.getAssigneeId();
    task.setAssigneeId(request.assigneeId());
    taskRepository.save(task);

    // Unassigning is not an assignment, so it raises no notification-bearing event.
    if (request.assigneeId() != null && !request.assigneeId().equals(previousAssignee)) {
      events.taskAssigned(task, previousAssignee, access.requireCurrentUser().id());
    }
    return toResponse(task);
  }

  @Transactional
  public void delete(UUID organizationId, UUID projectId, UUID taskId) {
    access.requireProjectWrite(organizationId, projectId);
    var task = loadScoped(projectId, taskId);

    // Subtasks and comments reference this row, so they go first.
    var subtasks = taskRepository.findByParentTaskId(taskId);
    for (var subtask : subtasks) {
      taskCommentRepository.deleteByTaskId(subtask.getId());
      taskLabelRepository.deleteByTaskId(subtask.getId());
    }
    taskRepository.deleteAll(subtasks);

    taskCommentRepository.deleteByTaskId(taskId);
    taskLabelRepository.deleteByTaskId(taskId);
    taskRepository.delete(task);

    log.info("Task {} deleted from project {}", taskId, projectId);
  }

  @Transactional
  public TaskResponse addLabel(UUID organizationId, UUID projectId, UUID taskId, String label) {
    access.requireProjectWrite(organizationId, projectId);
    var task = loadScoped(projectId, taskId);

    var normalised = label.trim();
    if (normalised.isEmpty()) {
      throw new IllegalArgumentException("A label cannot be blank");
    }
    if (taskLabelRepository.existsByTaskIdAndLabelIgnoreCase(taskId, normalised)) {
      throw new ResourceConflictException("That label is already on this task");
    }

    taskLabelRepository.save(com.devforge.ai.taskservice.entity.TaskLabelEntity.builder()
        .task(task)
        .label(normalised)
        .build());
    return toResponse(task);
  }

  @Transactional
  public TaskResponse removeLabel(UUID organizationId, UUID projectId, UUID taskId, String label) {
    access.requireProjectWrite(organizationId, projectId);
    var task = loadScoped(projectId, taskId);

    taskLabelRepository.findByTaskId(taskId).stream()
        .filter(existing -> existing.getLabel().equalsIgnoreCase(label))
        .findFirst()
        .ifPresent(taskLabelRepository::delete);
    return toResponse(task);
  }

  /** Resolves a task within its project, or reports it as absent. */
  private TaskEntity loadScoped(UUID projectId, UUID taskId) {
    return taskRepository.findByIdAndProjectId(taskId, projectId)
        .orElseThrow(() -> new ResourceNotFoundException("Task not found"));
  }

  TaskResponse toResponse(TaskEntity task) {
    var labels = taskLabelRepository.findByTaskId(task.getId()).stream()
        .map(com.devforge.ai.taskservice.entity.TaskLabelEntity::getLabel)
        .sorted()
        .toList();

    return new TaskResponse(
        task.getId(),
        task.getProjectId(),
        task.getOrganizationId(),
        task.getTaskNumber(),
        task.getTitle(),
        task.getDescription(),
        task.getStatus(),
        task.getPriority(),
        task.getType(),
        task.getAssigneeId(),
        task.getReporterId(),
        task.getStoryPoints(),
        task.getDueDate(),
        task.getParentTask() == null ? null : task.getParentTask().getId(),
        task.getSprint() == null ? null : task.getSprint().getId(),
        task.getBoardPosition(),
        labels,
        task.getCompletedAt(),
        task.getCreatedAt(),
        task.getUpdatedAt());
  }
}
