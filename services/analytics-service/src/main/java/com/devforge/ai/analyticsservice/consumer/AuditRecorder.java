package com.devforge.ai.analyticsservice.consumer;

import com.devforge.ai.analyticsservice.entity.AuditChain;
import com.devforge.ai.analyticsservice.entity.AuditChainHeadEntity;
import com.devforge.ai.analyticsservice.entity.AuditEntryEntity;
import com.devforge.ai.analyticsservice.repository.AuditEntryRepository;
import com.devforge.ai.common.events.EventEnvelope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Writes every consumed event to the audit log, whatever its type.
 *
 * <p>Unlike the metrics, nothing is filtered: an audit trail that recorded only the event types
 * someone remembered to list would silently miss the next one added. Runs inside the same
 * transaction as the processed-event marker, like {@link MetricsRecorder}, so an event is recorded
 * exactly once.
 */
@Component
@RequiredArgsConstructor
public class AuditRecorder {

  private final AuditEntryRepository entries;
  private final com.devforge.ai.analyticsservice.repository.AuditChainHeadRepository heads;
  private final ObjectMapper objectMapper;

  public void record(EventEnvelope<Map<String, Object>> envelope) {
    // Second line of defence behind the processed-event marker, as in notification-service.
    if (entries.existsByEventId(envelope.eventId())) {
      return;
    }
    var payload = envelope.payload() == null ? Map.<String, Object>of() : envelope.payload();
    var projectId = uuid(payload.get("projectId"));
    var chainKey = AuditChain.key(envelope.tenantId(), projectId);
    var entry = AuditEntryEntity.builder()
        .id(UUID.randomUUID())
        .eventId(envelope.eventId())
        .eventType(envelope.eventType())
        .source(envelope.source())
        .organizationId(envelope.tenantId())
        .projectId(projectId)
        .actorId(envelope.actorId())
        .occurredAt(AuditChain.storable(envelope.timestamp()))
        .details(json(payload))
        .chainKey(chainKey)
        .build();

    // A chain's first entry creates its head. Two threads starting the same chain at once both
    // insert it; the loser's transaction fails on the primary key and the consumer's retry
    // appends it behind the winner, which is the order it lost the race in anyway.
    var head = heads.lock(chainKey).orElseGet(() -> heads.save(new AuditChainHeadEntity(chainKey)));
    var sequence = head.getLastSequence() + 1;
    entry.chain(sequence, head.getLastHash());
    entries.save(entry);
    head.advance(sequence, entry.getEntryHash());
  }

  private String json(Map<String, Object> payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException ex) {
      // A payload that parsed off the wire will serialise again; this would be a bug.
      throw new IllegalStateException("Could not serialise an event payload for the audit log", ex);
    }
  }

  private static UUID uuid(Object value) {
    if (value == null) {
      return null;
    }
    try {
      return UUID.fromString(value.toString());
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }
}
