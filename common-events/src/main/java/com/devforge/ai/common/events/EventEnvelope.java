package com.devforge.ai.common.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/**
 * The single envelope every domain event travels in.
 *
 * <p>One shape for all events is the point: without it each service invents its own, and
 * consumers end up parsing several dialects. The payload varies, the envelope does not.
 *
 * <p>Marked {@link JsonIgnoreProperties} with {@code ignoreUnknown}: a producer running a newer
 * version may add envelope fields, and an older consumer must tolerate them rather than fail the
 * whole message. That, plus {@link #version}, is what makes rolling deployments survivable.
 *
 * @param eventId unique per event. Consumers deduplicate on this, so it must never be reused —
 *     including on a retry of the same publish.
 * @param eventType e.g. {@code UserRegistered}. See {@link EventTypes}.
 * @param version schema version of {@code payload}, starting at 1.
 * @param timestamp when the event occurred, not when it was published.
 * @param source the emitting service, e.g. {@code auth-service}.
 * @param tenantId owning organization, or null for platform-level events. Consumers must treat
 *     this as the authorization boundary and never widen it.
 * @param actorId the user who caused the event, or null for system-initiated ones.
 * @param correlationId ties an event to the request that produced it, across services.
 * @param payload event-specific body.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope<T>(
    @JsonProperty("eventId") UUID eventId,
    @JsonProperty("eventType") String eventType,
    @JsonProperty("version") int version,
    @JsonProperty("timestamp") Instant timestamp,
    @JsonProperty("source") String source,
    @JsonProperty("tenantId") UUID tenantId,
    @JsonProperty("actorId") UUID actorId,
    @JsonProperty("correlationId") String correlationId,
    @JsonProperty("payload") T payload) {

  /** Current envelope version for newly produced events. */
  public static final int CURRENT_VERSION = 1;

  @JsonCreator
  public EventEnvelope {
    // Validated at construction rather than at publish time: an envelope missing its type or
    // id is unroutable and undeduplicatable, and the cheapest place to catch that is here.
    if (eventId == null) {
      throw new IllegalArgumentException("eventId is required; consumers deduplicate on it");
    }
    if (eventType == null || eventType.isBlank()) {
      throw new IllegalArgumentException("eventType is required");
    }
    if (timestamp == null) {
      throw new IllegalArgumentException("timestamp is required");
    }
    if (version < 1) {
      throw new IllegalArgumentException("version must be >= 1");
    }
  }

  /** Builder-style factory for the common case. */
  public static <T> EventEnvelope<T> of(
      String eventType, String source, UUID tenantId, UUID actorId, String correlationId, T payload) {
    return new EventEnvelope<>(
        UUID.randomUUID(),
        eventType,
        CURRENT_VERSION,
        Instant.now(),
        source,
        tenantId,
        actorId,
        correlationId,
        payload);
  }

  /**
   * The Kafka partition key.
   *
   * <p>Keyed by tenant so all of one organization's events land on the same partition and are
   * therefore consumed in order. Ordering across tenants does not matter; ordering within one
   * does. Platform-level events fall back to the event id, which spreads them evenly.
   */
  public String partitionKey() {
    return tenantId != null ? tenantId.toString() : eventId.toString();
  }
}
