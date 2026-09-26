package com.devforge.ai.projectservice.dto;

import com.devforge.ai.projectservice.model.ProjectRole;
import com.devforge.ai.projectservice.model.ProjectStatus;
import java.time.Instant;
import java.util.UUID;

public record ProjectResponse(
    UUID id,
    UUID organizationId,
    String name,
    String projectKey,
    String description,
    ProjectStatus status,
    /** The requesting caller's effective role on this project. */
    ProjectRole role,
    UUID createdBy,
    Instant createdAt,
    Instant updatedAt) {}
