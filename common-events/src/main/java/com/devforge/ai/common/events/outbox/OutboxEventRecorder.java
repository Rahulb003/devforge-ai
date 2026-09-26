package com.devforge.ai.common.events.outbox;

import com.devforge.ai.common.events.EventEnvelope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stages a domain event for publication.
 *
 * <p>Deliberately does not talk to Kafka. It writes a row, and is designed to be called from
 * inside the business transaction that made the change — so the event and the state change commit
 * or roll back together. {@link OutboxPublisher} does the sending.
 *
 * <p>{@link Propagation#MANDATORY} enforces that: calling this without an active transaction is a
 * programming error, because the atomicity the outbox exists for would be lost. Failing loudly is
 * better than silently degrading to the inline-publish behaviour we are avoiding.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxEventRecorder {

  private final OutboxEventRepository outboxEventRepository;
  private final ObjectMapper objectMapper;

  @Value("${spring.application.name:unknown-service}")
  private String serviceName;

  /**
   * Records an event, to be published after the current transaction commits.
   *
   * @return the generated event id, which is also the consumer's deduplication key.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public <T> UUID record(
      String topic, String eventType, UUID tenantId, UUID actorId, String correlationId, T payload) {
    var envelope = EventEnvelope.of(eventType, serviceName, tenantId, actorId, correlationId, payload);
    return record(topic, envelope);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public <T> UUID record(String topic, EventEnvelope<T> envelope) {
    try {
      var serialised = objectMapper.writeValueAsString(envelope);
      outboxEventRepository.save(OutboxEvent.builder()
          .eventId(envelope.eventId())
          .eventType(envelope.eventType())
          .topic(topic)
          .partitionKey(envelope.partitionKey())
          .payload(serialised)
          .attempts(0)
          .build());

      log.debug("Recorded {} ({}) for topic {}", envelope.eventType(), envelope.eventId(), topic);
      return envelope.eventId();
    } catch (JsonProcessingException ex) {
      // Must fail the business transaction. Swallowing this would commit a state change whose
      // event never existed, which is precisely the inconsistency the outbox prevents.
      throw new IllegalStateException(
          "Unable to serialise event " + envelope.eventType(), ex);
    }
  }
}
