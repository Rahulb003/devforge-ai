package com.devforge.ai.analyticsservice.consumer;

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
  private final ObjectMapper objectMapper;

  public void record(EventEnvelope<Map<String, Object>> envelope) {
    // Second line of defence behind the processed-event marker, as in notification-service.
    if (entries.existsByEventId(envelope.eventId())) {
      return;
    }
    var payload = envelope.payload() == null ? Map.<String, Object>of() : envelope.payload();
    entries.save(AuditEntryEntity.builder()
        .id(UUID.randomUUID())
        .eventId(envelope.eventId())
        .eventType(envelope.eventType())
        .source(envelope.source())
        .organizationId(envelope.tenantId())
        .projectId(uuid(payload.get("projectId")))
        .actorId(envelope.actorId())
        .occurredAt(envelope.timestamp())
        .details(json(payload))
        .build());
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
