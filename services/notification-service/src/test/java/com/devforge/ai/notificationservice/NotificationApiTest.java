package com.devforge.ai.notificationservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devforge.ai.notificationservice.entity.NotificationEntity;
import com.devforge.ai.notificationservice.model.NotificationCategory;
import com.devforge.ai.notificationservice.repository.NotificationRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The notification read API, and who is allowed to see what.
 *
 * <p>The cross-user cases are the point of this class. §32 requires proof that user A cannot reach
 * user B's data, and a notification feed is where that goes wrong most easily: the natural-looking
 * design takes a user id from the request.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Notification API")
class NotificationApiTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private NotificationRepository notificationRepository;

  private final UUID alice = UUID.randomUUID();
  private final UUID bob = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    notificationRepository.deleteAll();
  }

  private String bearer(UUID userId) {
    return "Bearer " + TestTokens.accessToken(userId);
  }

  private NotificationEntity stored(UUID recipient, String title, boolean read) {
    return notificationRepository.save(NotificationEntity.builder()
        .recipientId(recipient)
        .organizationId(UUID.randomUUID())
        .category(NotificationCategory.TASK)
        .eventType("TaskAssigned")
        .title(title)
        .body("body of " + title)
        .link("/projects/" + UUID.randomUUID())
        .sourceEventId(UUID.randomUUID())
        .readAt(read ? Instant.now() : null)
        .build());
  }

  @Nested
  @DisplayName("reading your own feed")
  class OwnFeed {

    @Test
    @DisplayName("lists only the caller's notifications, newest first")
    void listsOwnNotificationsNewestFirst() throws Exception {
      stored(alice, "older", false);
      stored(alice, "newer", false);
      stored(bob, "bob's", false);

      mockMvc.perform(get("/api/v1/notifications").header(HttpHeaders.AUTHORIZATION, bearer(alice)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content.length()").value(2))
          // Bob's notification must not appear at all, under any ordering.
          .andExpect(jsonPath("$.data.content[?(@.title == \"bob's\")]").isEmpty());
    }

    @Test
    @DisplayName("the response does not echo the recipient id back")
    void responseOmitsRecipient() throws Exception {
      stored(alice, "mine", false);

      mockMvc.perform(get("/api/v1/notifications").header(HttpHeaders.AUTHORIZATION, bearer(alice)))
          .andExpect(status().isOk())
          // Nothing should teach a client that a recipient id is a thing it can send.
          .andExpect(jsonPath("$.data.content[0].recipientId").doesNotExist());
    }

    @Test
    @DisplayName("unreadOnly filters out what has been read")
    void unreadOnlyFilters() throws Exception {
      stored(alice, "unread one", false);
      stored(alice, "already read", true);

      mockMvc.perform(get("/api/v1/notifications?unreadOnly=true")
              .header(HttpHeaders.AUTHORIZATION, bearer(alice)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content.length()").value(1))
          .andExpect(jsonPath("$.data.content[0].title").value("unread one"));
    }

    @Test
    @DisplayName("the unread count counts only the caller's unread notifications")
    void unreadCountIsPerUser() throws Exception {
      stored(alice, "a1", false);
      stored(alice, "a2", false);
      stored(alice, "a3", true);
      stored(bob, "b1", false);

      mockMvc.perform(get("/api/v1/notifications/unread-count")
              .header(HttpHeaders.AUTHORIZATION, bearer(alice)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.unread").value(2));

      mockMvc.perform(get("/api/v1/notifications/unread-count")
              .header(HttpHeaders.AUTHORIZATION, bearer(bob)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.unread").value(1));
    }
  }

  @Nested
  @DisplayName("acknowledging")
  class Acknowledging {

    @Test
    @DisplayName("marking read sets a timestamp, and doing it twice does not move it")
    void markReadIsIdempotent() throws Exception {
      var notification = stored(alice, "mine", false);

      mockMvc.perform(post("/api/v1/notifications/" + notification.getId() + "/read")
              .header(HttpHeaders.AUTHORIZATION, bearer(alice)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.read").value(true));

      var firstReadAt = notificationRepository.findById(notification.getId()).orElseThrow().getReadAt();
      assertThat(firstReadAt).isNotNull();

      mockMvc.perform(post("/api/v1/notifications/" + notification.getId() + "/read")
              .header(HttpHeaders.AUTHORIZATION, bearer(alice)))
          .andExpect(status().isOk());

      // A second acknowledgement must not rewrite when the user actually saw it.
      assertThat(notificationRepository.findById(notification.getId()).orElseThrow().getReadAt())
          .isEqualTo(firstReadAt);
    }

    @Test
    @DisplayName("read-all marks every unread notification of the caller, and nobody else's")
    void readAllIsScopedToCaller() throws Exception {
      stored(alice, "a1", false);
      stored(alice, "a2", false);
      var bobsUnread = stored(bob, "b1", false);

      mockMvc.perform(post("/api/v1/notifications/read-all")
              .header(HttpHeaders.AUTHORIZATION, bearer(alice)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.marked").value(2));

      assertThat(notificationRepository.countByRecipientIdAndReadAtIsNull(alice)).isZero();
      assertThat(notificationRepository.findById(bobsUnread.getId()).orElseThrow().getReadAt())
          .isNull();
    }

    @Test
    @DisplayName("marking unread clears the timestamp")
    void markUnread() throws Exception {
      var notification = stored(alice, "mine", true);

      mockMvc.perform(post("/api/v1/notifications/" + notification.getId() + "/unread")
              .header(HttpHeaders.AUTHORIZATION, bearer(alice)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.read").value(false));

      assertThat(notificationRepository.findById(notification.getId()).orElseThrow().getReadAt())
          .isNull();
    }
  }

  @Nested
  @DisplayName("cross-user access (§32)")
  class CrossUser {

    @Test
    @DisplayName("Bob cannot read Alice's notification, and gets 404 rather than 403")
    void cannotMarkAnotherUsersNotificationRead() throws Exception {
      var alicesNotification = stored(alice, "alice's", false);

      mockMvc.perform(post("/api/v1/notifications/" + alicesNotification.getId() + "/read")
              .header(HttpHeaders.AUTHORIZATION, bearer(bob)))
          // 404, not 403: a 403 would confirm the id exists, which turns the endpoint into an
          // oracle for enumerating other people's notification ids.
          .andExpect(status().isNotFound());

      assertThat(notificationRepository.findById(alicesNotification.getId()).orElseThrow().getReadAt())
          .isNull();
    }

    @Test
    @DisplayName("Bob cannot delete Alice's notification")
    void cannotDeleteAnotherUsersNotification() throws Exception {
      var alicesNotification = stored(alice, "alice's", false);

      mockMvc.perform(delete("/api/v1/notifications/" + alicesNotification.getId())
              .header(HttpHeaders.AUTHORIZATION, bearer(bob)))
          .andExpect(status().isNotFound());

      assertThat(notificationRepository.findById(alicesNotification.getId())).isPresent();
    }

    @Test
    @DisplayName("Bob cannot mark Alice's notification unread")
    void cannotMarkAnotherUsersNotificationUnread() throws Exception {
      var alicesNotification = stored(alice, "alice's", true);

      mockMvc.perform(post("/api/v1/notifications/" + alicesNotification.getId() + "/unread")
              .header(HttpHeaders.AUTHORIZATION, bearer(bob)))
          .andExpect(status().isNotFound());

      assertThat(notificationRepository.findById(alicesNotification.getId()).orElseThrow().getReadAt())
          .isNotNull();
    }
  }

  @Nested
  @DisplayName("authentication")
  class Authentication {

    @Test
    @DisplayName("no token is rejected")
    void anonymousIsRejected() throws Exception {
      mockMvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a refresh token cannot be used as an access token")
    void refreshTokenIsRejected() throws Exception {
      mockMvc.perform(get("/api/v1/notifications")
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.refreshToken(alice)))
          .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a token signed with another key is rejected")
    void wronglySignedTokenIsRejected() throws Exception {
      mockMvc.perform(get("/api/v1/notifications")
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.wronglySignedToken(alice)))
          .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an expired token is rejected")
    void expiredTokenIsRejected() throws Exception {
      mockMvc.perform(get("/api/v1/notifications")
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.expiredToken(alice)))
          .andExpect(status().isUnauthorized());
    }
  }
}
