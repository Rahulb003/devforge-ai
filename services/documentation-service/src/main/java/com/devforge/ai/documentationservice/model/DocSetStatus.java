package com.devforge.ai.documentationservice.model;

/** Where a generation run got to. */
public enum DocSetStatus {
  RUNNING,
  COMPLETED,
  /** Generation could not finish — usually because the repository content was unreachable. */
  FAILED
}
