package com.devforge.ai.common.events.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

  /**
   * Claims a batch of unpublished events, oldest first.
   *
   * <p>{@code FOR UPDATE SKIP LOCKED} is what makes more than one instance of a service safe to
   * run: each poller locks a disjoint batch instead of every instance racing to publish the same
   * rows and producing duplicates. Without it, horizontal scaling multiplies every event.
   */
  @Query(value = """
      SELECT * FROM outbox_events
      WHERE published_at IS NULL
      ORDER BY created_at ASC
      LIMIT :batchSize
      FOR UPDATE SKIP LOCKED
      """, nativeQuery = true)
  List<OutboxEvent> claimUnpublished(@Param("batchSize") int batchSize);

  /**
   * Portable fallback used where SKIP LOCKED is unavailable (H2 in tests).
   *
   * <p>Not safe for concurrent publishers; only for single-instance or test use.
   */
  List<OutboxEvent> findByPublishedAtIsNullOrderByCreatedAtAsc(Pageable pageable);

  long countByPublishedAtIsNull();

  /** Sweeps successfully published rows so the table does not grow without bound. */
  @Modifying
  @Query("DELETE FROM OutboxEvent o WHERE o.publishedAt IS NOT NULL AND o.publishedAt < :before")
  int deletePublishedBefore(@Param("before") Instant before);

  /** Rows that have failed repeatedly, for alerting: these will not drain on their own. */
  List<OutboxEvent> findByPublishedAtIsNullAndAttemptsGreaterThan(int attempts);
}
