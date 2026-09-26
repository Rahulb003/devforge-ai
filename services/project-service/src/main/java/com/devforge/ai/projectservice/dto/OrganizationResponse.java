package com.devforge.ai.projectservice.dto;

import com.devforge.ai.projectservice.model.OrganizationRole;
import java.time.Instant;
import java.util.UUID;

public record OrganizationResponse(
    UUID id,
    String name,
    String slug,
    String description,
    /** The requesting caller's role. Never another user's. */
    OrganizationRole role,
    Instant createdAt) {}
