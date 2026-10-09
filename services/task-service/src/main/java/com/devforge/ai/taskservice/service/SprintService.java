package com.devforge.ai.taskservice.service;

import com.devforge.ai.common.exception.ResourceConflictException;
import com.devforge.ai.common.exception.ResourceNotFoundException;
import com.devforge.ai.taskservice.dto.CreateSprintRequest;
import com.devforge.ai.taskservice.dto.SprintResponse;
import com.devforge.ai.taskservice.entity.SprintEntity;
import com.devforge.ai.taskservice.model.SprintStatus;
import com.devforge.ai.taskservice.model.TaskStatus;
import com.devforge.ai.taskservice.repository.SprintRepository;
import com.devforge.ai.taskservice.repository.TaskRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SprintService {

  private final SprintRepository sprintRepository;
  private final TaskRepository taskRepository;
  private final TaskAccessService access;

  @Transactional
  public SprintResponse create(UUID organizationId, UUID projectId, CreateSprintRequest request) {
    access.requireProjectWrite(organizationId, projectId);
    var user = access.requireCurrentUser();

    if (request.startDate() != null
        && request.endDate() != null
        && request.endDate().isBefore(request.startDate())) {
      throw new IllegalArgumentException("A sprint cannot end before it starts");
    }

    var sprint = sprintRepository.save(SprintEntity.builder()
        .projectId(projectId)
        .organizationId(organizationId)
        .name(request.name())
        .goal(request.goal())
        // Created planned: starting it is an explicit act, and only one may be
        // active at a time.
        .status(SprintStatus.PLANNED)
        .startDate(request.startDate())
        .endDate(request.endDate())
        .createdBy(user.id())
        .build());

    return toResponse(sprint);
  }

  @Transactional(readOnly = true)
  public List<SprintResponse> list(UUID organizationId, UUID projectId) {
    access.requireProjectAccess(organizationId, projectId);
    return sprintRepository.findByProjectIdOrderByCreatedAtDescIdAsc(projectId).stream()
        .map(this::toResponse)
        .toList();
  }

  /**
   * Starts a sprint.
   *
   * <p>Refuses when another is already active. Two concurrent sprints make velocity and burndown
   * meaningless, and there is no rule for which one a newly created task would belong to.
   */
  @Transactional
  public SprintResponse start(UUID organizationId, UUID projectId, UUID sprintId) {
    access.requireProjectWrite(organizationId, projectId);
    var sprint = loadScoped(projectId, sprintId);

    if (sprint.getStatus() == SprintStatus.COMPLETED) {
      throw new ResourceConflictException("A completed sprint cannot be started again");
    }
    sprintRepository.findByProjectIdAndStatus(projectId, SprintStatus.ACTIVE)
        .filter(active -> !active.getId().equals(sprintId))
        .ifPresent(active -> {
          throw new ResourceConflictException(
              "Sprint '%s' is already active. Complete it first.".formatted(active.getName()));
        });

    sprint.setStatus(SprintStatus.ACTIVE);
    return toResponse(sprintRepository.save(sprint));
  }

  @Transactional
  public SprintResponse complete(UUID organizationId, UUID projectId, UUID sprintId) {
    access.requireProjectWrite(organizationId, projectId);
    var sprint = loadScoped(projectId, sprintId);

    if (sprint.getStatus() != SprintStatus.ACTIVE) {
      throw new ResourceConflictException("Only an active sprint can be completed");
    }

    sprint.setStatus(SprintStatus.COMPLETED);
    // Unfinished work is deliberately left attached rather than silently moved back
    // to the backlog: where it goes next is a decision for the team, and a silent
    // move loses the record of what was not finished.
    return toResponse(sprintRepository.save(sprint));
  }

  private SprintEntity loadScoped(UUID projectId, UUID sprintId) {
    return sprintRepository.findByIdAndProjectId(sprintId, projectId)
        .orElseThrow(() -> new ResourceNotFoundException("Sprint not found"));
  }

  private SprintResponse toResponse(SprintEntity sprint) {
    var tasks = taskRepository.findBySprintId(sprint.getId());
    var completed = tasks.stream().filter(task -> task.getStatus() == TaskStatus.DONE).count();

    return new SprintResponse(
        sprint.getId(),
        sprint.getProjectId(),
        sprint.getName(),
        sprint.getGoal(),
        sprint.getStatus(),
        sprint.getStartDate(),
        sprint.getEndDate(),
        tasks.size(),
        completed,
        sprint.getCreatedAt());
  }
}
