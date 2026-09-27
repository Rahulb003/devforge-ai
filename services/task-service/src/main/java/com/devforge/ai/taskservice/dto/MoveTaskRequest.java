package com.devforge.ai.taskservice.dto;

import com.devforge.ai.taskservice.model.TaskStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Moves a card on the board.
 *
 * @param status the column to move into
 * @param position zero-based index within that column. Null appends to the end.
 */
public record MoveTaskRequest(@NotNull TaskStatus status, Integer position) {}
