package com.devforge.ai.taskservice.events;

import com.devforge.ai.common.events.KafkaTopics;
import com.devforge.ai.common.events.outbox.OutboxEventRecorder;
import com.devforge.ai.taskservice.entity.TaskEntity;
import com.devforge.ai.taskservice.model.TaskStatus;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes task domain events through the outbox.
 *
 * <p>{@link Propagation#MANDATORY} throughout: these must run inside the transaction that made the
 * change, so the event and the change commit together. Called without one they fail loudly rather
 * than silently losing that guarantee.
 *
 * <p>Payloads carry ids and the fields a consumer needs to act — a notification service should not
 * have to call back for the task title. They carry nothing sensitive: an event is copied to every
 * consumer and retained by its topic.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskEventPublisher {

  /** Event type names. Constants, so a typo cannot silently stop a consumer matching. */
  public static final String TASK_CREATED = "TaskCreated";
  public static final String TASK_ASSIGNED = "TaskAssigned";
  public static final String TASK_MOVED = "TaskMoved";
  public static final String TASK_COMPLETED = "TaskCompleted";

  private final OutboxEventRecorder outbox;

  @Transactional(propagation = Propagation.MANDATORY)
  public void taskCreated(TaskEntity task, UUID actorId) {
    record(TASK_CREATED, task, actorId, Map.of(
        "status", task.getStatus().name(),
        "priority", task.getPriority().name(),
        "type", task.getType().name()));
  }

  /**
   * @param previousAssignee null when the task was previously unassigned. Included so a consumer
   *     can notify the person who lost the task as well as the one who gained it.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void taskAssigned(TaskEntity task, UUID previousAssignee, UUID actorId) {
    var payload = new HashMap<String, Object>();
    payload.put("previousAssigneeId", previousAssignee == null ? null : previousAssignee.toString());
    record(TASK_ASSIGNED, task, actorId, payload);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void taskMoved(TaskEntity task, TaskStatus previousStatus, UUID actorId) {
    record(TASK_MOVED, task, actorId, Map.of(
        "status", task.getStatus().name(),
        "previousStatus", previousStatus.name()));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void taskCompleted(TaskEntity task, UUID actorId) {
    var payload = new HashMap<String, Object>();
    payload.put("completedAt", task.getCompletedAt() == null ? null : task.getCompletedAt().toString());
    payload.put("storyPoints", task.getStoryPoints());
    record(TASK_COMPLETED, task, actorId, payload);
  }

  /**
   * @param actorId the user who performed the action.
   *     <p>This must be the acting user, not the task's reporter. The envelope's contract is "the
   *     user who caused the event", and consumers rely on it to avoid telling someone about their
   *     own action — so passing the reporter here meant anyone acting on a task they had not
   *     reported was announced as the reporter, and was then notified about what they had just
   *     done themselves.
   */
  private void record(String eventType, TaskEntity task, UUID actorId, Map<String, Object> extra) {
    var payload = new HashMap<String, Object>();
    payload.put("taskId", task.getId().toString());
    payload.put("projectId", task.getProjectId().toString());
    payload.put("taskNumber", task.getTaskNumber());
    payload.put("title", task.getTitle());
    // On every event, not just assignment: a consumer deciding who to tell needs to know who
    // holds the task, and TaskCompleted previously carried no way to find out.
    payload.put("assigneeId", task.getAssigneeId() == null ? null : task.getAssigneeId().toString());
    payload.put("reporterId", task.getReporterId() == null ? null : task.getReporterId().toString());
    payload.putAll(extra);

    outbox.record(
        KafkaTopics.TASKS,
        eventType,
        // Keyed by tenant, so one organization's task events stay ordered.
        task.getOrganizationId(),
        actorId,
        MDC.get("correlationId"),
        payload);
  }
}
