package com.devforge.ai.projectservice.dto;

import com.devforge.ai.projectservice.model.ProjectRole;
import java.time.Instant;
import java.util.UUID;

public record ProjectMemberResponse(UUID id, UUID userId, ProjectRole role, Instant createdAt) {}
