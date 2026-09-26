package com.devforge.ai.projectservice.dto;

import jakarta.validation.constraints.Size;

/** Partial update: null fields are left unchanged. */
public record UpdateProjectRequest(
    @Size(max = 150) String name,
    @Size(max = 2000) String description) {}
