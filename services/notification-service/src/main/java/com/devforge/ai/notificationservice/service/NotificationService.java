package com.devforge.ai.notificationservice.service;

import com.devforge.ai.common.exception.ResourceNotFoundException;
import com.devforge.ai.common.security.AuthenticatedUser;
import com.devforge.ai.notificationservice.dto.NotificationResponse;
import com.devforge.ai.notificationservice.entity.NotificationEntity;
import com.devforge.ai.notificationservice.repository.NotificationRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading and acknowledging one's own notifications.
 *
 * <p>The authorization model is the whole design: the recipient is always taken from the verified
 * access token and never from the request. There is no endpoint that accepts a user id, so there is
 * no parameter an attacker could change to read someone else's feed — the common shape of an IDOR
 * bug. A notification id belonging to another user resolves to nothing and returns 404 rather than
 * 403, so the API does not confirm that the id exists.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

  private final NotificationRepository notificationRepository;

  @Transactional(readOnly = true)
  public Page<NotificationResponse> list(boolean unreadOnly, Pageable pageable) {
    var recipientId = currentUserId();
    var page = unreadOnly
        ? notificationRepository.findByRecipientIdAndReadAtIsNullOrderByCreatedAtDesc(
            recipientId, pageable)
        : notificationRepository.findByRecipientIdOrderByCreatedAtDesc(recipientId, pageable);
    return page.map(NotificationResponse::from);
  }

  @Transactional(readOnly = true)
  public long unreadCount() {
    return notificationRepository.countByRecipientIdAndReadAtIsNull(currentUserId());
  }

  @Transactional
  public NotificationResponse markRead(UUID notificationId) {
    var notification = loadOwned(notificationId);
    // Idempotent: marking an already-read notification must not move the timestamp, or a second
    // click would misreport when the user actually saw it.
    if (!notification.isRead()) {
      notification.setReadAt(Instant.now());
      notificationRepository.save(notification);
    }
    return NotificationResponse.from(notification);
  }

  @Transactional
  public NotificationResponse markUnread(UUID notificationId) {
    var notification = loadOwned(notificationId);
    if (notification.isRead()) {
      notification.setReadAt(null);
      notificationRepository.save(notification);
    }
    return NotificationResponse.from(notification);
  }

  /** @return how many were still unread and have now been marked. */
  @Transactional
  public int markAllRead() {
    return notificationRepository.markAllRead(currentUserId(), Instant.now());
  }

  @Transactional
  public void delete(UUID notificationId) {
    notificationRepository.delete(loadOwned(notificationId));
  }

  /**
   * Loads a notification that belongs to the caller.
   *
   * <p>Scoped by recipient in the query rather than fetched and then checked, so there is no
   * version of this method that can return someone else's row.
   */
  private NotificationEntity loadOwned(UUID notificationId) {
    return notificationRepository
        .findByIdAndRecipientId(notificationId, currentUserId())
        .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));
  }

  private UUID currentUserId() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.isAuthenticated()
        && authentication.getPrincipal() instanceof AuthenticatedUser user) {
      return user.id();
    }
    throw new AccessDeniedException("Not authenticated");
  }
}
