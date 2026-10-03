package com.devforge.ai.notificationservice.dto;

import com.devforge.ai.notificationservice.entity.NotificationEntity;
import com.devforge.ai.notificationservice.model.NotificationCategory;
import java.time.Instant;
import java.util.UUID;

/**
 * A notification as the owner sees it.
 *
 * <p>{@code recipientId} is not exposed: the caller is always the recipient, so returning it would
 * be noise at best, and at worst an invitation to build a client that passes it back as a filter.
 *
 * @param link relative path to whatever the notification refers to, or null when there is nowhere
 *     useful to go.
 */
public record NotificationResponse(
    UUID id,
    UUID organizationId,
    NotificationCategory category,
    String eventType,
    String title,
    String body,
    String link,
    boolean read,
    Instant readAt,
    Instant createdAt) {

  public static NotificationResponse from(NotificationEntity entity) {
    return new NotificationResponse(
        entity.getId(),
        entity.getOrganizationId(),
        entity.getCategory(),
        entity.getEventType(),
        entity.getTitle(),
        entity.getBody(),
        entity.getLink(),
        entity.isRead(),
        entity.getReadAt(),
        entity.getCreatedAt());
  }
}
