package com.devforge.ai.projectservice.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Adds an existing platform user to a project with a role. */
public record AddMemberRequest(
    @NotNull UUID userId,
    @NotNull com.devforge.ai.projectservice.model.ProjectRole role) {}
