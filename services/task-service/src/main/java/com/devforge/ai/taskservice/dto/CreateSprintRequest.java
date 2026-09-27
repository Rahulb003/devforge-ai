package com.devforge.ai.taskservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record CreateSprintRequest(
    @NotBlank @Size(max = 150) String name,
    @Size(max = 1000) String goal,
    LocalDate startDate,
    LocalDate endDate) {}
