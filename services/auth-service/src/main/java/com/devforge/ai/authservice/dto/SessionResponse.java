package com.devforge.ai.authservice.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One active session, as shown on the security screen.
 *
 * <p>Carries no token value. The session is addressed by its id for revocation, so the response
 * never has to contain a live credential.
 */
public record SessionResponse(
    UUID id,
    String deviceLabel,
    String userAgent,
    String ipAddress,
    Instant createdAt,
    Instant lastUsedAt,
    Instant expiresAt,
    boolean current) {}
