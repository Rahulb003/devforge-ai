package com.devforge.ai.reviewservice.analysis;

import com.devforge.ai.reviewservice.model.ReviewModel.FindingCategory;
import com.devforge.ai.reviewservice.model.ReviewModel.Severity;

/**
 * One thing a rule noticed, before it is persisted.
 *
 * @param lineNumber 1-based, or null when the finding is about the file as a whole.
 * @param snippet the offending line, already redacted where it contained a credential. A finding
 *     that quotes a secret copies it into the database, the API response and the logs — turning a
 *     detection into a second place the secret leaks from. Redaction is the rule's job, not the
 *     caller's, because only the rule knows which part of the line is sensitive.
 */
public record Finding(
    String ruleId,
    Severity severity,
    FindingCategory category,
    String filePath,
    Integer lineNumber,
    String message,
    String snippet) {

  /** Truncates a snippet to something a UI can show, from the middle of a long minified line. */
  public static String snippetOf(String line, int max) {
    if (line == null) {
      return null;
    }
    var trimmed = line.strip();
    return trimmed.length() <= max ? trimmed : trimmed.substring(0, max - 1) + "…";
  }
}
