package com.devforge.ai.taskservice.dto;

import com.devforge.ai.taskservice.model.TaskPriority;
import com.devforge.ai.taskservice.model.TaskType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

public record CreateTaskRequest(
    @NotBlank @Size(max = 300) String title,
    @Size(max = 10000) String description,
    TaskPriority priority,
    TaskType type,
    UUID assigneeId,
    @Min(0) @Max(1000) Integer storyPoints,
    LocalDate dueDate,
    UUID parentTaskId,
    UUID sprintId) {}
