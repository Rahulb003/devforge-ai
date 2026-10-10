package com.devforge.ai.notificationservice.service;

import com.devforge.ai.common.events.EventEnvelope;
import com.devforge.ai.common.events.EventTypes;
import com.devforge.ai.notificationservice.entity.NotificationEntity;
import com.devforge.ai.notificationservice.model.NotificationCategory;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Decides who should be told about an event, and what to say.
 *
 * <p>This is the only place that knows the shape of another service's event payloads, so when a
 * producer adds a field there is one file to change rather than a listener full of map lookups.
 *
 * <p>Returning a list rather than a single notification is deliberate: one event can legitimately
 * concern two people. Reassigning a task is the obvious case — the person who gained it and the
 * person who lost it both want to know.
 *
 * <p>An unrecognised event type produces no notifications rather than an error. A consumer that
 * rejects event types it has not been taught about would start dead-lettering the moment any
 * producer ships a new one, and "notifications are down" is a worse outcome than "that event does
 * not notify anyone yet".
 */
@Slf4j
@Component
public class NotificationFactory {

  /** Task event types, named by task-service. Duplicated as constants rather than imported,
   * because depending on task-service's jar for four strings would couple the two services'
   * build graphs for no benefit. */
  static final String TASK_ASSIGNED = "TaskAssigned";
  static final String TASK_COMPLETED = "TaskCompleted";

  public List<NotificationEntity> from(EventEnvelope<Map<String, Object>> envelope) {
    var payload = envelope.payload() == null ? Map.<String, Object>of() : envelope.payload();

    return switch (envelope.eventType()) {
      case TASK_ASSIGNED -> taskAssigned(envelope, payload);
      case TASK_COMPLETED -> taskCompleted(envelope, payload);
      case EventTypes.PULL_REQUEST_MERGED -> pullRequestDecided(envelope, payload, "merged");
      case EventTypes.PULL_REQUEST_CLOSED -> pullRequestDecided(envelope, payload, "closed");
      case EventTypes.PROJECT_MEMBER_ADDED -> membership(envelope, payload, true);
      case EventTypes.PROJECT_MEMBER_REMOVED -> membership(envelope, payload, false);
      case EventTypes.ORGANIZATION_MEMBER_ROLE_CHANGED -> organizationRole(envelope, payload);
      case EventTypes.ORGANIZATION_MEMBER_REMOVED -> organizationRemoval(envelope, payload);
      case EventTypes.USER_PASSWORD_RESET -> securityAlert(envelope, payload,
          "Your password was changed",
          "If this was not you, reset your password and sign out every device immediately.");
      case EventTypes.USER_MFA_ENABLED -> securityAlert(envelope, payload,
          "Two-factor authentication enabled",
          "Your account now requires a verification code at sign-in.");
      case EventTypes.USER_MFA_DISABLED -> securityAlert(envelope, payload,
          "Two-factor authentication disabled",
          "Your account no longer requires a verification code. If this was not you, re-enable it "
              + "and change your password.");
      case EventTypes.REFRESH_TOKEN_REUSE_DETECTED -> securityAlert(envelope, payload,
          "You were signed out of all devices",
          "We detected a sign-in token being reused, which can mean it was stolen. Every session "
              + "was ended as a precaution.");
      default -> {
        log.debug("No notification mapped for event type {}", envelope.eventType());
        yield List.of();
      }
    };
  }

  private List<NotificationEntity> taskAssigned(
      EventEnvelope<Map<String, Object>> envelope, Map<String, Object> payload) {

    var assignee = uuid(payload.get("assigneeId"));
    var previous = uuid(payload.get("previousAssigneeId"));
    var label = taskLabel(payload);
    var link = taskLink(envelope, payload);
    var notifications = new java.util.ArrayList<NotificationEntity>(2);

    // Someone assigning a task to themselves does not need telling. This is the single most
    // common way a notification feed becomes noise people learn to ignore.
    if (assignee != null && !assignee.equals(envelope.actorId())) {
      notifications.add(build(envelope, assignee, NotificationCategory.TASK,
          "You were assigned " + label,
          text(payload.get("title")),
          link));
    }

    // The person who lost the task, so work does not silently move away from them.
    if (previous != null && !previous.equals(assignee) && !previous.equals(envelope.actorId())) {
      notifications.add(build(envelope, previous, NotificationCategory.TASK,
          label + " was reassigned",
          "This task is no longer assigned to you.",
          link));
    }

    return notifications;
  }

  /**
   * Telling someone they were added to a project, or taken off one.
   *
   * <p>Until now a new member had no way to learn about it short of looking at their project list.
   * The admin who made the change already knows, so adding yourself notifies nobody. Removal links
   * nowhere: the person can no longer open the project.
   */
  private List<NotificationEntity> membership(
      EventEnvelope<Map<String, Object>> envelope, Map<String, Object> payload, boolean added) {
    var member = uuid(payload.get("userId"));
    var projectId = text(payload.get("projectId"));
    if (member == null || member.equals(envelope.actorId())) {
      return List.of();
    }
    var name = text(payload.get("projectName"));
    var project = name == null ? "a project" : name;
    if (!added) {
      return List.of(build(envelope, member, NotificationCategory.PROJECT,
          "You were removed from " + project, "You no longer have access to it.", null));
    }
    var role = text(payload.get("role"));
    var link = envelope.tenantId() == null || projectId == null ? null
        : "/organizations/" + envelope.tenantId() + "/projects/" + projectId;
    return List.of(build(envelope, member, NotificationCategory.PROJECT,
        "You were added to " + project,
        role == null ? "You can now open it from your projects." : "Your role: " + roleLabel(role) + ".",
        link));
  }

  /** A changed organization role, told to the person whose role it is. */
  private List<NotificationEntity> organizationRole(
      EventEnvelope<Map<String, Object>> envelope, Map<String, Object> payload) {
    var member = uuid(payload.get("userId"));
    var role = text(payload.get("role"));
    if (member == null || role == null || member.equals(envelope.actorId())) {
      return List.of();
    }
    var name = text(payload.get("organizationName"));
    return List.of(build(envelope, member, NotificationCategory.PROJECT,
        "You are now " + roleLabel(role).toLowerCase(java.util.Locale.ROOT) + " of "
            + (name == null ? "an organization" : name),
        null,
        envelope.tenantId() == null ? null : "/organizations/" + envelope.tenantId()));
  }

  /** Removed by someone else; leaving on your own needs no notice. */
  private List<NotificationEntity> organizationRemoval(
      EventEnvelope<Map<String, Object>> envelope, Map<String, Object> payload) {
    var member = uuid(payload.get("userId"));
    if (member == null || member.equals(envelope.actorId())) {
      return List.of();
    }
    var name = text(payload.get("organizationName"));
    return List.of(build(envelope, member, NotificationCategory.PROJECT,
        "You were removed from " + (name == null ? "an organization" : name),
        "You no longer have access to its projects.", null));
  }

  private static String roleLabel(String role) {
    var words = role.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    return Character.toUpperCase(words.charAt(0)) + words.substring(1);
  }

  private List<NotificationEntity> taskCompleted(
      EventEnvelope<Map<String, Object>> envelope, Map<String, Object> payload) {

    var assignee = uuid(payload.get("assigneeId"));
    // The actor completed it, so they already know. Only tell someone else involved.
    if (assignee == null || assignee.equals(envelope.actorId())) {
      return List.of();
    }
    return List.of(build(envelope, assignee, NotificationCategory.TASK,
        taskLabel(payload) + " was completed",
        text(payload.get("title")),
        taskLink(envelope, payload)));
  }

  /**
   * Security alerts always go to the account they concern.
   *
   * <p>The recipient is the subject of the event, not the actor: a password reset completed through
   * a reset link has no authenticated actor, and the whole value of the alert is that it reaches
   * the account owner even when someone else triggered it.
   */
  private List<NotificationEntity> securityAlert(
      EventEnvelope<Map<String, Object>> envelope,
      Map<String, Object> payload,
      String title,
      String body) {

    var userId = uuid(payload.get("userId"));
    if (userId == null) {
      userId = envelope.actorId();
    }
    if (userId == null) {
      // Nothing to deliver to. Logged at warn because a security event that cannot be
      // attributed is worth noticing rather than dropping quietly.
      log.warn("Security event {} ({}) carried no user id; no notification created",
          envelope.eventType(), envelope.eventId());
      return List.of();
    }
    // "/settings", the page that exists. This was "/settings/security", which no route serves,
    // so every security alert linked to the not-found page.
    return List.of(build(envelope, userId, NotificationCategory.SECURITY, title, body,
        "/settings"));
  }

  private NotificationEntity build(
      EventEnvelope<Map<String, Object>> envelope,
      UUID recipientId,
      NotificationCategory category,
      String title,
      String body,
      String link) {

    return NotificationEntity.builder()
        .recipientId(recipientId)
        // Security events are platform-level and carry no tenant; task events carry one.
        .organizationId(envelope.tenantId())
        .category(category)
        .eventType(envelope.eventType())
        .title(truncate(title, 200))
        .body(truncate(body, 1000))
        .link(truncate(link, 500))
        .sourceEventId(envelope.eventId())
        .correlationId(envelope.correlationId())
        .build();
  }

  /** e.g. {@code task #12}. Falls back to the id when the producer sent no number. */
  private String taskLabel(Map<String, Object> payload) {
    var number = payload.get("taskNumber");
    return number == null ? "a task" : "task #" + number;
  }

  /**
   * The task on its project board, which opens it from {@code ?task=}.
   *
   * <p>This was {@code /projects/{p}/tasks/{t}}, a route the app has never had, so every task
   * notification linked to the not-found page. Board URLs need the organization too: it is the
   * event's tenant.
   */
  private String taskLink(EventEnvelope<Map<String, Object>> envelope, Map<String, Object> payload) {
    var projectId = text(payload.get("projectId"));
    var taskId = text(payload.get("taskId"));
    if (envelope.tenantId() == null || projectId == null || taskId == null) {
      return null;
    }
    return "/organizations/" + envelope.tenantId() + "/projects/" + projectId + "?task=" + taskId;
  }

  /**
   * Tells a pull request's author that someone else merged or closed it. Their own action needs
   * no notice, as with tasks.
   */
  private List<NotificationEntity> pullRequestDecided(
      EventEnvelope<Map<String, Object>> envelope, Map<String, Object> payload, String outcome) {
    var author = uuid(payload.get("authorId"));
    if (author == null || author.equals(envelope.actorId())) {
      return List.of();
    }
    var number = payload.get("number");
    var projectId = text(payload.get("projectId"));
    var repositoryId = text(payload.get("repositoryId"));
    var link = envelope.tenantId() == null || projectId == null || repositoryId == null || number == null
        ? null
        : "/organizations/" + envelope.tenantId() + "/projects/" + projectId + "/repositories/"
            + repositoryId + "/pull-requests/" + number;
    return List.of(build(envelope, author, NotificationCategory.CODE,
        "Your pull request #" + number + " was " + outcome,
        text(payload.get("title")),
        link));
  }

  private static UUID uuid(Object value) {
    if (value == null) {
      return null;
    }
    try {
      return UUID.fromString(value.toString());
    } catch (IllegalArgumentException ex) {
      // A malformed id in a payload is the producer's bug. Returning null drops the recipient
      // rather than dead-lettering the event, so one bad field cannot stop the rest.
      return null;
    }
  }

  private static String text(Object value) {
    return value == null ? null : value.toString();
  }

  /**
   * Keeps generated text inside the column width.
   *
   * <p>Titles contain user-supplied task titles, and the database would reject an over-long value —
   * which, on a consumer, means a dead-lettered event rather than a failed request.
   */
  private static String truncate(String value, int max) {
    if (value == null || value.length() <= max) {
      return value;
    }
    return value.substring(0, max - 1) + "…";
  }
}
