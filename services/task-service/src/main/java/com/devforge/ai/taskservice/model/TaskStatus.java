package com.devforge.ai.taskservice.model;

/** Workflow state of a task. Matches the Kanban columns. */
public enum TaskStatus {
  BACKLOG,
  TODO,
  IN_PROGRESS,
  IN_REVIEW,
  DONE,
  BLOCKED;

  /** Whether reaching this state means the work is finished. */
  public boolean isTerminal() {
    return this == DONE;
  }
}
