package com.devforge.ai.taskservice.dto;

import com.devforge.ai.taskservice.model.TaskPriority;
import com.devforge.ai.taskservice.model.TaskStatus;
import com.devforge.ai.taskservice.model.TaskType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record TaskResponse(
    UUID id,
    UUID projectId,
    UUID organizationId,
    Integer taskNumber,
    String title,
    String description,
    TaskStatus status,
    TaskPriority priority,
    TaskType type,
    UUID assigneeId,
    UUID reporterId,
    Integer storyPoints,
    LocalDate dueDate,
    UUID parentTaskId,
    UUID sprintId,
    Integer boardPosition,
    List<String> labels,
    Instant completedAt,
    Instant createdAt,
    Instant updatedAt) {}
