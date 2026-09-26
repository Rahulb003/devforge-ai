package com.devforge.ai.common.events.outbox;

import com.devforge.ai.common.events.EventEnvelope;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs an event handler at most once per consumer group.
 *
 * <p>Duplicates are normal, not exceptional: the outbox is at-least-once, and Kafka redelivers
 * whenever a rebalance interrupts an offset commit. Handlers with side effects — sending mail,
 * incrementing counters, inserting rows — produce visible damage on a second run, so they are
 * wrapped here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotentEventProcessor {

  private final ProcessedEventRepository processedEventRepository;

  /**
   * Executes {@code handler} unless this event has already been processed by this consumer group.
   *
   * <p>The marker is written in the same transaction as the handler, so a handler that throws
   * rolls the marker back and the event is retried rather than being recorded as done. Recording
   * completion before running the handler would turn any handler failure into a permanently
   * skipped event.
   *
   * @return true if the handler ran, false if the event was a duplicate.
   */
  @Transactional
  public <T> boolean processOnce(
      EventEnvelope<T> envelope, String consumerGroup, Consumer<EventEnvelope<T>> handler) {

    if (processedEventRepository.existsByEventIdAndConsumerGroup(envelope.eventId(), consumerGroup)) {
      log.debug("Skipping duplicate {} ({}) for group {}",
          envelope.eventType(), envelope.eventId(), consumerGroup);
      return false;
    }

    handler.accept(envelope);

    try {
      processedEventRepository.save(ProcessedEvent.builder()
          .eventId(envelope.eventId())
          .consumerGroup(consumerGroup)
          .eventType(envelope.eventType())
          .build());
    } catch (DataIntegrityViolationException ex) {
      // Two consumers in the same group raced on the same event and both passed the exists()
      // check. The unique key is the real arbiter; the loser must not fail the batch, because
      // the work is already done.
      log.debug("Concurrent processing of {} in group {}; marker already present",
          envelope.eventId(), consumerGroup);
    }
    return true;
  }
}
