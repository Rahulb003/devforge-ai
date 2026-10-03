package com.devforge.ai.notificationservice.controller;

import com.devforge.ai.common.web.ApiResponse;
import com.devforge.ai.notificationservice.dto.NotificationResponse;
import com.devforge.ai.notificationservice.service.NotificationService;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own notifications.
 *
 * <p>The route carries no user id, and that is the security design rather than an omission. An
 * endpoint such as {@code /users/{id}/notifications} invites exactly the bug §32 warns about: the
 * id becomes something a client sends, and every method then has to remember to check it. Here the
 * recipient comes from the verified token, so there is no parameter to tamper with.
 *
 * <p>Unlike tasks and projects, these are not addressed under an organization. A notification can
 * concern the account itself — a password change, a disabled second factor — which belongs to no
 * tenant, and forcing those under an organization path would mean inventing one.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class NotificationController {

  private final NotificationService notificationService;

  @GetMapping
  public ResponseEntity<ApiResponse<Page<NotificationResponse>>> list(
      @RequestParam(defaultValue = "false") boolean unreadOnly,
      @PageableDefault(size = 20) Pageable pageable) {
    return ResponseEntity.ok(
        new ApiResponse<>(true, notificationService.list(unreadOnly, pageable), null));
  }

  /**
   * Just the badge count.
   *
   * <p>Separate from the list because the UI polls this far more often than it opens the feed, and
   * a count is a single indexed query rather than a page of rows.
   */
  @GetMapping("/unread-count")
  public ResponseEntity<ApiResponse<Map<String, Long>>> unreadCount() {
    return ResponseEntity.ok(
        new ApiResponse<>(true, Map.of("unread", notificationService.unreadCount()), null));
  }

  @PostMapping("/{notificationId}/read")
  public ResponseEntity<ApiResponse<NotificationResponse>> markRead(
      @PathVariable UUID notificationId) {
    return ResponseEntity.ok(
        new ApiResponse<>(true, notificationService.markRead(notificationId), null));
  }

  @PostMapping("/{notificationId}/unread")
  public ResponseEntity<ApiResponse<NotificationResponse>> markUnread(
      @PathVariable UUID notificationId) {
    return ResponseEntity.ok(
        new ApiResponse<>(true, notificationService.markUnread(notificationId), null));
  }

  @PostMapping("/read-all")
  public ResponseEntity<ApiResponse<Map<String, Integer>>> markAllRead() {
    return ResponseEntity.ok(new ApiResponse<>(
        true, Map.of("marked", notificationService.markAllRead()), "Notifications marked as read"));
  }

  @DeleteMapping("/{notificationId}")
  public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID notificationId) {
    notificationService.delete(notificationId);
    return ResponseEntity.ok(new ApiResponse<>(true, null, "Notification deleted"));
  }
}
