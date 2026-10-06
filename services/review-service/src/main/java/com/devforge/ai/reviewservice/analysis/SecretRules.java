package com.devforge.ai.reviewservice.analysis;

import com.devforge.ai.common.git.RepositoryFile;
import com.devforge.ai.reviewservice.model.ReviewModel.FindingCategory;
import com.devforge.ai.reviewservice.model.ReviewModel.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Detects credentials committed to a repository.
 *
 * <p>The most valuable rule in the set, and the one that justifies {@code BLOCKER}: a key in git
 * history is leaked even after it is deleted, because the history keeps it. Catching it before it
 * merges is worth a false positive now and then.
 *
 * <p>Patterns are anchored on vendor prefixes wherever one exists ({@code AKIA}, {@code ghp_},
 * {@code xoxb-}) rather than on entropy. High-entropy heuristics flag hashes, UUIDs, minified
 * JavaScript and base64 test fixtures, and a rule people learn to ignore protects nothing.
 *
 * <p><strong>Findings never quote the credential.</strong> Every snippet here is redacted, because
 * the finding is stored, returned by the API and written to logs — a rule that echoed the secret
 * would create three new copies of it.
 */
@Component
public class SecretRules implements AnalysisRule {

  private record SecretPattern(String ruleId, String description, Pattern pattern) {}

  private static final List<SecretPattern> PATTERNS = List.of(
      new SecretPattern(
          "SECRET_AWS_ACCESS_KEY_ID",
          "an AWS access key id",
          // AKIA (long-lived user key) and ASIA (temporary session key).
          Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b")),
      new SecretPattern(
          "SECRET_PRIVATE_KEY",
          "a private key",
          Pattern.compile("-----BEGIN (?:RSA |DSA |EC |OPENSSH |PGP )?PRIVATE KEY(?: BLOCK)?-----")),
      new SecretPattern(
          "SECRET_GITHUB_TOKEN",
          "a GitHub token",
          // ghp_ personal, gho_ oauth, ghs_ server, ghu_ user-to-server, github_pat_ fine-grained.
          Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{22,})\\b")),
      new SecretPattern(
          "SECRET_SLACK_TOKEN",
          "a Slack token",
          Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b")),
      new SecretPattern(
          "SECRET_GOOGLE_API_KEY",
          "a Google API key",
          Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b")),
      new SecretPattern(
          "SECRET_STRIPE_KEY",
          "a Stripe secret key",
          Pattern.compile("\\b(?:sk|rk)_(?:live|test)_[0-9A-Za-z]{10,}\\b")),
      new SecretPattern(
          "SECRET_URL_CREDENTIALS",
          "credentials embedded in a URL",
          // scheme://user:password@host — the password is in the authority component.
          Pattern.compile("\\b[a-zA-Z][a-zA-Z0-9+.-]*://[^\\s:/@]+:[^\\s:/@]+@[^\\s/]+")),
      new SecretPattern(
          "SECRET_ASSIGNED_LITERAL",
          "a hard-coded credential",
          // A credential-shaped name assigned a non-trivial literal. Deliberately requires a
          // quoted value of some length, so `password = userInput` and `password = ""` do not match.
          Pattern.compile(
              "(?i)\\b(?:password|passwd|secret|api[_-]?key|access[_-]?token|auth[_-]?token"
                  + "|client[_-]?secret|private[_-]?key)\\b\\s*[:=]\\s*[\"'][^\"'\\s]{8,}[\"']")));

  /**
   * Values that look like credentials but are placeholders.
   *
   * <p>Without this, every example file and test fixture in the repository is a BLOCKER, and the
   * gate gets disabled within a day.
   */
  private static final Pattern PLACEHOLDER = Pattern.compile(
      "(?i)(?:change[_-]?me"
          // "your-api-key-here", "yourPassword", "your_token": anything claiming to be the
          // reader's own value. Allows words between "your" and the noun, which the first version
          // did not, so "your-api-key-here" was reported as a real credential.
          + "|your[_-]?(?:[a-z0-9]+[_-]?){0,3}(?:password|secret|key|token|credential)"
          + "|example|placeholder|dummy|redacted|x{5,}|<[^>]+>|\\$\\{[^}]+\\}|\\*{4,}"
          + "|todo|sample|not[_-]?a[_-]?real|fake|test[_-]?only)");

  @Override
  public List<Finding> analyse(RepositoryFile file) {
    var findings = new ArrayList<Finding>();
    var lines = file.lines();

    for (int i = 0; i < lines.size(); i++) {
      var line = lines.get(i);
      for (var candidate : PATTERNS) {
        var matcher = candidate.pattern().matcher(line);
        if (!matcher.find()) {
          continue;
        }
        // Checked against the MATCH, not the whole line.
        //
        // Scanning the line let anything elsewhere on it suppress the finding: a minified bundle
        // containing a run of x characters hid a real credential further along the same line, which
        // is a false negative and the worst kind for this rule. The placeholder has to be the
        // credential itself to count.
        if (PLACEHOLDER.matcher(matcher.group()).find()) {
          continue;
        }
        findings.add(new Finding(
            candidate.ruleId(),
            Severity.BLOCKER,
            FindingCategory.SECRET,
            file.path(),
            i + 1,
            "This line appears to contain " + candidate.description()
                + ". A credential committed to git is leaked even after it is removed, because the "
                + "history keeps it — rotate it rather than only deleting the line.",
            redact(line, matcher.start(), matcher.end())));
        // One finding per line is enough; a line with two matches is still one line to fix, and
        // reporting both would double-count it in the gate.
        break;
      }
    }
    return findings;
  }

  /**
   * Replaces the matched credential with a marker, keeping the surrounding line for context.
   *
   * <p>Shows at most the first four characters of the match — enough to recognise which key it is
   * when rotating it, not enough to use.
   */
  private static String redact(String line, int start, int end) {
    var match = line.substring(start, end);
    var hint = match.length() > 4 ? match.substring(0, 4) : "";
    var replacement = hint + "[REDACTED " + (end - start) + " chars]";
    return Finding.snippetOf(line.substring(0, start) + replacement + line.substring(end), 300);
  }
}
