package com.devforge.ai.taskservice.dto;

import com.devforge.ai.taskservice.model.TaskStatus;
import java.util.List;

/** One Kanban column with its cards already in board order. */
public record BoardColumn(TaskStatus status, List<TaskResponse> tasks) {}
