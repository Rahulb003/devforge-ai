package com.devforge.ai.reviewservice.model;

/** Enumerations shared across the review domain, grouped because each is a few lines. */
public final class ReviewModel {

  private ReviewModel() {}

  public enum ReviewStatus {
    RUNNING,
    COMPLETED,
    /** Analysis could not finish — usually because repository content was unreachable. */
    FAILED
  }

  /**
   * Whether the review passes the quality gate.
   *
   * <p>Deliberately two values and not a score. A number invites arguing about the threshold; a
   * gate forces the question "does this block the change or not?", which is the decision anyone
   * actually has to make.
   */
  public enum GateResult {
    PASS,
    FAIL
  }

  /**
   * How much a finding matters.
   *
   * <p>{@code BLOCKER} is reserved for things that are almost never acceptable to merge — a
   * committed credential above all. Keeping that bar high is what stops the gate being switched off.
   */
  public enum Severity {
    BLOCKER,
    HIGH,
    MEDIUM,
    LOW,
    INFO
  }

  public enum FindingCategory {
    /** A credential appears in the content. */
    SECRET,
    /** A file that should never be committed, judged by its name. */
    CREDENTIAL_FILE,
    /** Evidence of a botched merge. */
    MERGE_CONFLICT,
    /** A construct that is a common source of vulnerabilities. */
    DANGEROUS_PATTERN,
    /** Repository hygiene rather than correctness. */
    MAINTAINABILITY
  }
}
