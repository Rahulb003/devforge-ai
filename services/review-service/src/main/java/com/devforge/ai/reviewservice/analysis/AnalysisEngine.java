package com.devforge.ai.reviewservice.analysis;

import com.devforge.ai.common.git.RepositoryFile;
import com.devforge.ai.reviewservice.model.ReviewModel.GateResult;
import com.devforge.ai.reviewservice.model.ReviewModel.Severity;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Runs every rule over every file and decides whether the result passes.
 *
 * <p>Rules are injected as a list, so adding a rule is adding a bean — there is no registry to keep
 * in step, which is the usual way a new rule silently never runs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisEngine {

  private final List<AnalysisRule> rules;

  /** Any finding at or above this severity fails the gate. */
  @Value("${devforge.review.gate.fail-at-severity:BLOCKER}")
  private Severity failAtSeverity;

  /**
   * Findings per file, after which the rest are dropped.
   *
   * <p>A generated or minified file can match a pattern on every one of fifty thousand lines. One
   * review would then hold fifty thousand rows describing one problem, which is useless to read and
   * expensive to store.
   */
  @Value("${devforge.review.max-findings-per-file:50}")
  private int maxFindingsPerFile;

  @Value("${devforge.review.max-findings-per-review:1000}")
  private int maxFindingsPerReview;

  public Result analyse(List<RepositoryFile> files) {
    var all = new ArrayList<Finding>();
    var truncated = false;

    for (var file : files) {
      if (all.size() >= maxFindingsPerReview) {
        truncated = true;
        break;
      }

      var perFile = new ArrayList<Finding>();
      for (var rule : rules) {
        try {
          perFile.addAll(rule.analyse(file));
        } catch (RuntimeException ex) {
          // One rule failing must not fail the review. Repository content is hostile input, so a
          // rule meeting something it cannot handle is a bug to fix, not a reason to deny the
          // user every other finding.
          log.error("Rule {} failed on {}: {}",
              rule.getClass().getSimpleName(), file.path(), ex.getMessage(), ex);
        }
      }

      if (perFile.size() > maxFindingsPerFile) {
        // Keep the most severe, so truncation never hides a BLOCKER behind a hundred LOWs.
        perFile.sort(java.util.Comparator.comparing(Finding::severity));
        perFile = new ArrayList<>(perFile.subList(0, maxFindingsPerFile));
        truncated = true;
      }
      all.addAll(perFile);
    }

    var counts = new EnumMap<Severity, Integer>(Severity.class);
    for (var severity : Severity.values()) {
      counts.put(severity, 0);
    }
    for (var finding : all) {
      counts.merge(finding.severity(), 1, Integer::sum);
    }

    var gate = GateResult.PASS;
    for (var severity : Severity.values()) {
      if (severity.ordinal() <= failAtSeverity.ordinal() && counts.get(severity) > 0) {
        gate = GateResult.FAIL;
        break;
      }
    }

    return new Result(all, counts, gate, files.size(), truncated);
  }

  /**
   * @param counts every severity, including zeros, so a caller does not have to handle absence.
   * @param truncated true when findings were dropped by a limit above.
   */
  public record Result(
      List<Finding> findings,
      Map<Severity, Integer> counts,
      GateResult gate,
      int filesAnalysed,
      boolean truncated) {}
}
