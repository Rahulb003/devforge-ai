package com.devforge.ai.notificationservice.repository;

import com.devforge.ai.notificationservice.entity.NotificationEntity;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Every method is scoped by recipient.
 *
 * <p>There is intentionally no {@code findById(UUID)} in use anywhere in this service: a lookup by
 * id alone would return another user's notification, and the caller would then have to remember to
 * check ownership. Scoping the query instead makes the unsafe version impossible to call by
 * accident, and a wrong id simply resolves to nothing.
 */
public interface NotificationRepository extends JpaRepository<NotificationEntity, UUID> {

  Page<NotificationEntity> findByRecipientIdOrderByCreatedAtDesc(UUID recipientId, Pageable pageable);

  Page<NotificationEntity> findByRecipientIdAndReadAtIsNullOrderByCreatedAtDesc(
      UUID recipientId, Pageable pageable);

  Optional<NotificationEntity> findByIdAndRecipientId(UUID id, UUID recipientId);

  long countByRecipientIdAndReadAtIsNull(UUID recipientId);

  /**
   * Marks everything unread as read in one statement.
   *
   * <p>A read-modify-write loop would race with events arriving at the same moment and could mark
   * a notification the user never saw. The {@code read_at IS NULL} predicate also makes this
   * idempotent: running it twice does not move timestamps that were already set.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE NotificationEntity n SET n.readAt = :now "
      + "WHERE n.recipientId = :recipientId AND n.readAt IS NULL")
  int markAllRead(@Param("recipientId") UUID recipientId, @Param("now") Instant now);

  /** Used by the consumer to avoid writing a second copy of a notification it already created. */
  boolean existsBySourceEventIdAndRecipientId(UUID sourceEventId, UUID recipientId);
}
