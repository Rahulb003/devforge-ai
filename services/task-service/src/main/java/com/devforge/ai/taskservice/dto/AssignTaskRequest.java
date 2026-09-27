package com.devforge.ai.taskservice.dto;

import java.util.UUID;

/** Assigns or, with a null id, unassigns the task. */
public record AssignTaskRequest(UUID assigneeId) {}
