package com.devforge.ai.projectservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
    @NotBlank @Size(max = 150) String name,
    @NotBlank @Size(min = 2, max = 20)
    @Pattern(regexp = "^[A-Z][A-Z0-9]*$",
        message = "must be uppercase letters and digits, starting with a letter")
    String projectKey,
    @Size(max = 2000) String description) {}
