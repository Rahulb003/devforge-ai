package com.devforge.ai.notificationservice.service;

import com.devforge.ai.common.events.EventEnvelope;
import com.devforge.ai.notificationservice.repository.NotificationRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Turns an event into stored notifications.
 *
 * <p>Separate from the listener so the decision of <em>what</em> to write is testable without a
 * broker, and separate from {@link NotificationFactory} so the decision of <em>who to tell</em> has
 * no database dependency.
 *
 * <p>Deliberately not {@code @Transactional}: it is called inside the transaction
 * {@code IdempotentEventProcessor} opens, so that the rows and the processed-event marker commit
 * together. Opening another here would let a handler succeed while its marker rolled back, and the
 * event would then be delivered again and duplicated.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationWriter {

  private final NotificationFactory factory;
  private final NotificationRepository notificationRepository;

  public void write(EventEnvelope<Map<String, Object>> envelope) {
    var notifications = factory.from(envelope);
    if (notifications.isEmpty()) {
      return;
    }

    for (var notification : notifications) {
      // Second line of defence behind the processed-event marker. The marker is scoped to the
      // consumer group, so it cannot help if this service is ever scaled out under two group ids,
      // or if an operator replays a topic deliberately. This check is cheap and makes a replay
      // harmless rather than duplicating every notification in the recipient's list.
      if (notificationRepository.existsBySourceEventIdAndRecipientId(
          notification.getSourceEventId(), notification.getRecipientId())) {
        log.debug("Notification for event {} and recipient {} already exists",
            notification.getSourceEventId(), notification.getRecipientId());
        continue;
      }
      notificationRepository.save(notification);
    }

    log.debug("Stored {} notification(s) from {} ({})",
        notifications.size(), envelope.eventType(), envelope.eventId());
  }
}
