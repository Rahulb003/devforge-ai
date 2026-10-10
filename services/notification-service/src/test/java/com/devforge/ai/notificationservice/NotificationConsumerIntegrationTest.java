package com.devforge.ai.notificationservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.devforge.ai.common.events.EventEnvelope;
import com.devforge.ai.common.events.EventTypes;
import com.devforge.ai.common.events.KafkaTopics;
import com.devforge.ai.notificationservice.entity.NotificationEntity;
import com.devforge.ai.notificationservice.model.NotificationCategory;
import com.devforge.ai.notificationservice.repository.NotificationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

/**
 * The consumer, driven by a real broker.
 *
 * <p>This is the first production consumer on the platform, so it is tested the way it runs:
 * messages are produced to Kafka and the assertions are made on what ended up in the database. A
 * test that called the handler directly would not exercise deserialisation, the listener container,
 * or the deduplication that at-least-once delivery makes mandatory.
 */
@SpringBootTest
@EmbeddedKafka(
    partitions = 1,
    topics = {KafkaTopics.IDENTITY, KafkaTopics.SECURITY, KafkaTopics.TASKS, KafkaTopics.REPOSITORIES,
        KafkaTopics.PROJECTS})
@TestPropertySource(properties = {
    // The listener is off by default in tests so the API tests need no broker; this class
    // supplies one and switches it on.
    "devforge.kafka.enabled=true",
    "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
})
@DisplayName("Notification consumer against a real Kafka broker")
class NotificationConsumerIntegrationTest {

  @Autowired private KafkaTemplate<String, String> kafkaTemplate;
  @Autowired private NotificationRepository notificationRepository;
  @Autowired private ObjectMapper objectMapper;

  private final UUID organizationId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    notificationRepository.deleteAll();
  }

  @Test
  @DisplayName("assigning a task notifies the assignee, not the person who assigned it")
  void taskAssignedNotifiesAssignee() {
    var assigner = UUID.randomUUID();
    var assignee = UUID.randomUUID();
    var taskId = UUID.randomUUID();
    var projectId = UUID.randomUUID();

    publish(KafkaTopics.TASKS, taskEvent("TaskAssigned", assigner, Map.of(
        "taskId", taskId.toString(),
        "projectId", projectId.toString(),
        "taskNumber", 12,
        "title", "Harden the CORS configuration",
        "assigneeId", assignee.toString())));

    await(() -> notificationRepository.countByRecipientIdAndReadAtIsNull(assignee) == 1,
        "the assignee to be notified");

    var notification = onlyNotificationFor(assignee);
    assertThat(notification.getTitle()).isEqualTo("You were assigned task #12");
    assertThat(notification.getBody()).isEqualTo("Harden the CORS configuration");
    assertThat(notification.getCategory()).isEqualTo(NotificationCategory.TASK);
    assertThat(notification.getOrganizationId()).isEqualTo(organizationId);
    // A route the app serves: the board opens the task from ?task=. This used to assert a
    // "/projects/..." link, which locked in a path no route ever served.
    assertThat(notification.getLink()).isEqualTo(
        "/organizations/" + organizationId + "/projects/" + projectId + "?task=" + taskId);
    assertThat(notification.isRead()).isFalse();

    // The person who performed the action already knows they did it.
    assertThat(notificationRepository.countByRecipientIdAndReadAtIsNull(assigner)).isZero();
  }

  @Test
  @DisplayName("a pull request merged by someone else notifies its author; merging your own does not")
  void pullRequestDecisionNotifiesAuthor() {
    var author = UUID.randomUUID();
    var merger = UUID.randomUUID();
    var projectId = UUID.randomUUID();
    var repositoryId = UUID.randomUUID();
    var payload = Map.<String, Object>of(
        "repositoryId", repositoryId.toString(),
        "projectId", projectId.toString(),
        "pullRequestId", UUID.randomUUID().toString(),
        "number", 7,
        "authorId", author.toString(),
        "title", "Add login");

    publish(KafkaTopics.REPOSITORIES, taskEvent("PullRequestMerged", merger, payload));
    await(() -> notificationRepository.countByRecipientIdAndReadAtIsNull(author) == 1,
        "the author to be notified");

    var notification = onlyNotificationFor(author);
    assertThat(notification.getTitle()).isEqualTo("Your pull request #7 was merged");
    assertThat(notification.getBody()).isEqualTo("Add login");
    assertThat(notification.getCategory()).isEqualTo(NotificationCategory.CODE);
    assertThat(notification.getLink()).isEqualTo("/organizations/" + organizationId + "/projects/"
        + projectId + "/repositories/" + repositoryId + "/pull-requests/7");
    assertThat(notificationRepository.countByRecipientIdAndReadAtIsNull(merger)).isZero();

    // The author closing their own pull request needs no notice.
    publish(KafkaTopics.REPOSITORIES, taskEvent("PullRequestClosed", author, payload));
    // A marker after it on the same single-partition consumer: once the marker is processed, the
    // close has been too, so "still one notification" means the close created none.
    var marker = UUID.randomUUID();
    publish(KafkaTopics.REPOSITORIES, taskEvent("PullRequestMerged", merger, Map.<String, Object>of(
        "repositoryId", repositoryId.toString(), "projectId", projectId.toString(),
        "pullRequestId", UUID.randomUUID().toString(), "number", 8,
        "authorId", marker.toString(), "title", "marker")));
    await(() -> notificationRepository.countByRecipientIdAndReadAtIsNull(marker) == 1,
        "the marker to be processed");
    assertThat(notificationRepository.countByRecipientIdAndReadAtIsNull(author)).isEqualTo(1);
  }

  @Test
  @DisplayName("being added to a project, or removed, tells the member - not the admin who did it")
  void membershipNotifiesTheMember() {
    var admin = UUID.randomUUID();
    var member = UUID.randomUUID();
    var projectId = UUID.randomUUID();
    Map<String, Object> payload = Map.of("projectId", projectId.toString(), "projectName", "Apollo",
        "userId", member.toString(), "role", "TEAM_LEAD");

    publish(KafkaTopics.PROJECTS, taskEvent("ProjectMemberAdded", admin, payload));
    await(() -> notificationRepository.countByRecipientIdAndReadAtIsNull(member) == 1,
        "the new member to be notified");
    var added = onlyNotificationFor(member);
    assertThat(added.getTitle()).isEqualTo("You were added to Apollo");
    assertThat(added.getBody()).isEqualTo("Your role: Team lead.");
    assertThat(added.getCategory()).isEqualTo(NotificationCategory.PROJECT);
    assertThat(added.getLink()).isEqualTo("/organizations/" + organizationId + "/projects/" + projectId);

    publish(KafkaTopics.PROJECTS, taskEvent("ProjectMemberRemoved", admin, payload));
    await(() -> notificationRepository.countByRecipientIdAndReadAtIsNull(member) == 2,
        "the removal to be notified");
    assertThat(notificationRepository.countByRecipientIdAndReadAtIsNull(admin)).isZero();

    // An admin adding themselves needs no notice.
    publish(KafkaTopics.PROJECTS, taskEvent("ProjectMemberAdded", admin, Map.<String, Object>of(
        "projectId", projectId.toString(), "projectName", "Apollo",
        "userId", admin.toString(), "role", "ADMIN")));
    settle();
    assertThat(notificationRepository.countByRecipientIdAndReadAtIsNull(admin)).isZero();
  }

  @Test
  @DisplayName("a changed organization role, or removal by someone else, tells the member")
  void organizationMembershipNotifies() {
    var admin = UUID.randomUUID();
    var member = UUID.randomUUID();

    publish(KafkaTopics.PROJECTS, taskEvent("OrganizationMemberRoleChanged", admin, Map.of(
        "userId", member.toString(), "role", "ADMIN", "previousRole", "MEMBER",
        "organizationName", "Acme")));
    await(() -> notificationRepository.countByRecipientIdAndReadAtIsNull(member) == 1,
        "the role change to be notified");
    var changed = onlyNotificationFor(member);
    assertThat(changed.getTitle()).isEqualTo("You are now admin of Acme");
    assertThat(changed.getLink()).isEqualTo("/organizations/" + organizationId);

    publish(KafkaTopics.PROJECTS, taskEvent("OrganizationMemberRemoved", admin, Map.of(
        "userId", member.toString(), "role", "ADMIN", "organizationName", "Acme")));
    await(() -> notificationRepository.countByRecipientIdAndReadAtIsNull(member) == 2,
        "the removal to be notified");

    // Leaving on your own: the actor is the member, so nothing is sent.
    var leaver = UUID.randomUUID();
    publish(KafkaTopics.PROJECTS, taskEvent("OrganizationMemberRemoved", leaver, Map.of(
        "userId", leaver.toString(), "role", "MEMBER", "organizationName", "Acme")));
    settle();
    assertThat(notificationRepository.countByRecipientIdAndReadAtIsNull(leaver)).isZero();
  }

  @Test
  @DisplayName("assigning a task to yourself notifies nobody")
  void selfAssignmentNotifiesNobody() {
    var user = UUID.randomUUID();

    publish(KafkaTopics.TASKS, taskEvent("TaskAssigned", user, Map.of(
        "taskId", UUID.randomUUID().toString(),
        "projectId", UUID.randomUUID().toString(),
        "taskNumber", 3,
        "title", "Pick this up myself",
        "assigneeId", user.toString())));

    // Nothing to wait for, so wait for a quiet period instead and assert it stayed empty.
    settle();
    assertThat(notificationRepository.count()).isZero();
  }

  @Test
  @DisplayName("reassignment tells both the new assignee and the one who lost it")
  void reassignmentNotifiesBothParties() {
    var actor = UUID.randomUUID();
    var previous = UUID.randomUUID();
    var next = UUID.randomUUID();

    publish(KafkaTopics.TASKS, taskEvent("TaskAssigned", actor, Map.of(
        "taskId", UUID.randomUUID().toString(),
        "projectId", UUID.randomUUID().toString(),
        "taskNumber", 7,
        "title", "Move the gateway routes",
        "assigneeId", next.toString(),
        "previousAssigneeId", previous.toString())));

    await(() -> notificationRepository.count() == 2, "both parties to be notified");

    assertThat(onlyNotificationFor(next).getTitle()).isEqualTo("You were assigned task #7");
    assertThat(onlyNotificationFor(previous).getTitle()).isEqualTo("task #7 was reassigned");
  }

  @Test
  @DisplayName("a password reset raises a security alert on the account it concerns")
  void passwordResetRaisesSecurityAlert() {
    var user = UUID.randomUUID();

    // No tenant and no actor: a reset completed through an emailed link has neither, and the
    // alert still has to reach the account owner. That is the whole point of the alert.
    publish(KafkaTopics.IDENTITY, new EventEnvelope<Map<String, Object>>(
        UUID.randomUUID(),
        EventTypes.USER_PASSWORD_RESET,
        EventEnvelope.CURRENT_VERSION,
        Instant.now(),
        "auth-service",
        null,
        null,
        "corr-reset",
        Map.of("userId", user.toString())));

    await(() -> notificationRepository.countByRecipientIdAndReadAtIsNull(user) == 1,
        "the account owner to be alerted");

    var notification = onlyNotificationFor(user);
    assertThat(notification.getCategory()).isEqualTo(NotificationCategory.SECURITY);
    assertThat(notification.getTitle()).isEqualTo("Your password was changed");
    assertThat(notification.getOrganizationId()).isNull();
    assertThat(notification.getCorrelationId()).isEqualTo("corr-reset");
  }

  @Test
  @DisplayName("a deleted account's notifications are removed, and only that account's")
  void deletedAccountLosesItsNotifications() {
    var deleted = UUID.randomUUID();
    var other = UUID.randomUUID();
    for (var user : java.util.List.of(deleted, other)) {
      publish(KafkaTopics.IDENTITY, new EventEnvelope<Map<String, Object>>(UUID.randomUUID(),
          EventTypes.USER_PASSWORD_RESET, EventEnvelope.CURRENT_VERSION, Instant.now(), "auth-service",
          null, null, null, Map.of("userId", user.toString())));
    }
    await(() -> notificationRepository.countByRecipientIdAndReadAtIsNull(deleted) == 1
        && notificationRepository.countByRecipientIdAndReadAtIsNull(other) == 1, "both alerts");

    publish(KafkaTopics.IDENTITY, new EventEnvelope<Map<String, Object>>(UUID.randomUUID(),
        EventTypes.USER_DELETED, EventEnvelope.CURRENT_VERSION, Instant.now(), "auth-service",
        null, deleted, null, Map.of("userId", deleted.toString())));

    await(() -> notificationRepository.countByRecipientIdAndReadAtIsNull(deleted) == 0,
        "the deleted account's notifications to go");
    assertThat(notificationRepository.countByRecipientIdAndReadAtIsNull(other)).isEqualTo(1);
  }

  @Test
  @DisplayName("detected token reuse alerts the user that sessions were ended")
  void tokenReuseRaisesSecurityAlert() {
    var user = UUID.randomUUID();

    publish(KafkaTopics.SECURITY, new EventEnvelope<Map<String, Object>>(
        UUID.randomUUID(),
        EventTypes.REFRESH_TOKEN_REUSE_DETECTED,
        EventEnvelope.CURRENT_VERSION,
        Instant.now(),
        "auth-service",
        null,
        user,
        "corr-reuse",
        Map.of("userId", user.toString(), "action", "ALL_SESSIONS_REVOKED")));

    await(() -> notificationRepository.countByRecipientIdAndReadAtIsNull(user) == 1,
        "the user to be alerted about token reuse");

    assertThat(onlyNotificationFor(user).getCategory()).isEqualTo(NotificationCategory.SECURITY);
  }

  @Test
  @DisplayName("the same event delivered twice produces one notification")
  void redeliveryDoesNotDuplicate() {
    var assignee = UUID.randomUUID();
    var envelope = taskEvent("TaskAssigned", UUID.randomUUID(), Map.of(
        "taskId", UUID.randomUUID().toString(),
        "projectId", UUID.randomUUID().toString(),
        "taskNumber", 21,
        "title", "Verify the event backbone",
        "assigneeId", assignee.toString()));

    // What at-least-once delivery actually looks like: the outbox resends after a crash between
    // a successful send and the row being marked published.
    publish(KafkaTopics.TASKS, envelope);
    publish(KafkaTopics.TASKS, envelope);

    await(() -> notificationRepository.count() >= 1, "the first notification to be stored");
    settle();

    assertThat(notificationRepository.count()).isEqualTo(1);
    assertThat(onlyNotificationFor(assignee).getTitle()).isEqualTo("You were assigned task #21");
  }

  @Test
  @DisplayName("an event type nobody has mapped is ignored rather than dead-lettered")
  void unmappedEventTypeIsIgnored() {
    publish(KafkaTopics.IDENTITY, new EventEnvelope<Map<String, Object>>(
        UUID.randomUUID(),
        EventTypes.USER_LOGGED_IN,
        EventEnvelope.CURRENT_VERSION,
        Instant.now(),
        "auth-service",
        null,
        UUID.randomUUID(),
        "corr-login",
        Map.of("userId", UUID.randomUUID().toString())));

    settle();
    // A consumer that rejected unknown types would start dead-lettering the moment any producer
    // shipped a new one, and "notifications are down" is worse than "that event notifies nobody".
    assertThat(notificationRepository.count()).isZero();
  }

  @Test
  @DisplayName("an unreadable message does not stop the events behind it")
  void malformedMessageDoesNotBlockThePartition() {
    var assignee = UUID.randomUUID();

    // Same partition key, so the valid event is strictly behind the broken one.
    var key = organizationId.toString();
    kafkaTemplate.send(KafkaTopics.TASKS, key, "{ this is not an event envelope");
    publish(KafkaTopics.TASKS, taskEvent("TaskAssigned", UUID.randomUUID(), Map.of(
        "taskId", UUID.randomUUID().toString(),
        "projectId", UUID.randomUUID().toString(),
        "taskNumber", 99,
        "title", "Still gets through",
        "assigneeId", assignee.toString())));

    await(() -> notificationRepository.countByRecipientIdAndReadAtIsNull(assignee) == 1,
        "the valid event behind the malformed one to be processed");
  }

  // ---------------------------------------------------------------- helpers

  private EventEnvelope<Map<String, Object>> taskEvent(
      String eventType, UUID actorId, Map<String, Object> payload) {
    return new EventEnvelope<>(
        UUID.randomUUID(),
        eventType,
        EventEnvelope.CURRENT_VERSION,
        Instant.now(),
        "task-service",
        organizationId,
        actorId,
        "corr-" + eventType,
        payload);
  }

  private void publish(String topic, EventEnvelope<Map<String, Object>> envelope) {
    try {
      kafkaTemplate
          .send(topic, envelope.partitionKey(), objectMapper.writeValueAsString(envelope))
          .get(20, TimeUnit.SECONDS);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while publishing", ex);
    } catch (Exception ex) {
      throw new AssertionError("Failed to publish test event", ex);
    }
  }

  private NotificationEntity onlyNotificationFor(UUID recipient) {
    List<NotificationEntity> all = notificationRepository.findAll().stream()
        .filter(n -> n.getRecipientId().equals(recipient))
        .toList();
    assertThat(all).hasSize(1);
    return all.get(0);
  }

  /**
   * Waits for a condition, polling rather than sleeping a fixed time.
   *
   * <p>Consumer-group assignment takes an unpredictable moment on a cold broker, so a fixed sleep
   * is either flaky or needlessly slow.
   */
  private void await(BooleanSupplier condition, String description) {
    var deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      pause(100);
    }
    throw new AssertionError("Timed out waiting for " + description
        + " (stored: " + notificationRepository.count() + ")");
  }

  /**
   * Gives the consumer time to do something wrong.
   *
   * <p>Needed for the assertions that nothing should be written: with no state change to wait for,
   * the only way to distinguish "correctly ignored" from "not processed yet" is to let the
   * listener finish and then check. Deliberately generous, because a short wait here would turn
   * into a test that passes for the wrong reason.
   */
  private void settle() {
    pause(3_000);
  }

  private void pause(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while waiting", ex);
    }
  }
}
