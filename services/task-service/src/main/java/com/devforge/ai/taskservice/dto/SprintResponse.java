package com.devforge.ai.taskservice.dto;

import com.devforge.ai.taskservice.model.SprintStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record SprintResponse(
    UUID id,
    UUID projectId,
    String name,
    String goal,
    SprintStatus status,
    LocalDate startDate,
    LocalDate endDate,
    /** Totals for the sprint, so a burndown does not need a second request. */
    long totalTasks,
    long completedTasks,
    Instant createdAt) {}
