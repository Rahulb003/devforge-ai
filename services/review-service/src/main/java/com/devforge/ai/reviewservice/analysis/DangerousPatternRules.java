package com.devforge.ai.reviewservice.analysis;

import com.devforge.ai.common.git.RepositoryFile;
import com.devforge.ai.reviewservice.model.ReviewModel.FindingCategory;
import com.devforge.ai.reviewservice.model.ReviewModel.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Constructs that are a recurring source of vulnerabilities.
 *
 * <p>Deliberately a short list of high-signal patterns rather than an attempt at a general static
 * analyser. Each one here is a construct where the safe alternative is well known and the unsafe
 * form is rarely deliberate — so the finding tells the reader what to do instead, not just that
 * something was spotted.
 *
 * <p>Severity is {@code MEDIUM}: these need a human to judge context. Marking them higher would
 * block merges on patterns that are occasionally correct, and a gate that cries wolf gets turned
 * off — which costs more than the findings are worth.
 */
@Component
public class DangerousPatternRules implements AnalysisRule {

  private record DangerPattern(String ruleId, String advice, Pattern pattern, Set<String> extensions) {}

  private static final Set<String> JS = Set.of("js", "jsx", "ts", "tsx", "mjs", "cjs");
  private static final Set<String> JAVA = Set.of("java");
  private static final Set<String> PYTHON = Set.of("py");
  private static final Set<String> ANY = Set.of();

  private static final List<DangerPattern> PATTERNS = List.of(
      new DangerPattern(
          "DANGEROUS_EVAL",
          "eval() executes whatever string it is given. If any part of that string comes from "
              + "input, it is remote code execution. Parse the value instead.",
          Pattern.compile("(?<![\\w.])eval\\s*\\("),
          JS),
      new DangerPattern(
          "DANGEROUS_INNER_HTML",
          "dangerouslySetInnerHTML bypasses React's escaping. Unless the HTML is sanitised, any "
              + "user-supplied value in it becomes stored XSS.",
          Pattern.compile("dangerouslySetInnerHTML"),
          JS),
      new DangerPattern(
          "DANGEROUS_RUNTIME_EXEC",
          "Building a command line from a string invites injection. Pass arguments as a list, or "
              + "better, call a library instead of a process.",
          Pattern.compile("Runtime\\.getRuntime\\(\\)\\.exec\\s*\\("),
          JAVA),
      new DangerPattern(
          "DANGEROUS_SQL_CONCATENATION",
          "Concatenating a value into SQL is how injection happens. Use a bound parameter.",
          // A SQL keyword followed by string concatenation with a variable.
          Pattern.compile(
              "(?i)\"\\s*(?:select|insert|update|delete)\\b[^\"]*\"\\s*\\+\\s*\\w"),
          ANY),
      new DangerPattern(
          "DANGEROUS_PICKLE_LOAD",
          "pickle deserialises arbitrary objects and will execute code while doing so. Use JSON "
              + "for data that crosses a trust boundary.",
          Pattern.compile("pickle\\.loads?\\s*\\("),
          PYTHON),
      new DangerPattern(
          "DANGEROUS_YAML_LOAD",
          "yaml.load without SafeLoader can construct arbitrary Python objects. Use yaml.safe_load.",
          Pattern.compile("yaml\\.load\\s*\\((?![^)]*SafeLoader)"),
          PYTHON),
      new DangerPattern(
          "DANGEROUS_TLS_VERIFICATION_DISABLED",
          "Disabling certificate verification removes the protection TLS exists for: the "
              + "connection is then trivially interceptable.",
          Pattern.compile(
              "(?i)(?:verify\\s*=\\s*False|rejectUnauthorized\\s*:\\s*false"
                  + "|InsecureSkipVerify\\s*:\\s*true|--insecure\\b|ALLOW_ALL_HOSTNAME_VERIFIER)"),
          ANY));

  @Override
  public List<Finding> analyse(RepositoryFile file) {
    if (file.binary()) {
      return List.of();
    }

    var findings = new ArrayList<Finding>();
    var lines = file.lines();

    for (var candidate : PATTERNS) {
      if (!candidate.extensions().isEmpty() && !candidate.extensions().contains(file.extension())) {
        continue;
      }
      for (int i = 0; i < lines.size(); i++) {
        var line = lines.get(i);
        // Cheap comment filter. A pattern named in a comment — including in this very file — is
        // discussion, not code, and flagging it makes the rule look broken to whoever reads it.
        var stripped = line.strip();
        if (stripped.startsWith("//") || stripped.startsWith("*") || stripped.startsWith("#")
            || stripped.startsWith("/*")) {
          continue;
        }
        if (candidate.pattern().matcher(line).find()) {
          findings.add(new Finding(
              candidate.ruleId(),
              Severity.MEDIUM,
              FindingCategory.DANGEROUS_PATTERN,
              file.path(),
              i + 1,
              candidate.advice(),
              Finding.snippetOf(line, 160)));
        }
      }
    }
    return findings;
  }
}
