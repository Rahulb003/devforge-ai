package com.devforge.ai.projectservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Payload for creating an organization. The creator becomes its OWNER. */
public record CreateOrganizationRequest(
    @NotBlank @Size(max = 150) String name,
    // Lowercase, URL-safe, no leading/trailing or doubled separators.
    @NotBlank @Size(min = 2, max = 100)
    @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$",
        message = "must be lowercase alphanumeric words separated by single hyphens")
    String slug,
    @Size(max = 1000) String description) {}
