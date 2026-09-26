package com.devforge.ai.common.events.outbox;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ProcessedEventRepository
    extends JpaRepository<ProcessedEvent, ProcessedEvent.ProcessedEventId> {

  boolean existsByEventIdAndConsumerGroup(UUID eventId, String consumerGroup);

  /**
   * Prunes old markers.
   *
   * <p>The retention must comfortably exceed the topic's own retention: if a marker is deleted
   * while the event can still be redelivered, the duplicate protection silently lapses.
   */
  @Modifying
  @Query("DELETE FROM ProcessedEvent p WHERE p.processedAt < :before")
  int deleteProcessedBefore(@Param("before") Instant before);
}
