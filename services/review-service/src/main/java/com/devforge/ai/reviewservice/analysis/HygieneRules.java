package com.devforge.ai.reviewservice.analysis;

import com.devforge.ai.common.git.RepositoryFile;
import com.devforge.ai.reviewservice.model.ReviewModel.FindingCategory;
import com.devforge.ai.reviewservice.model.ReviewModel.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Rules about what a file is, rather than what is inside it.
 *
 * <p>Judging by filename catches a class of mistake the content rules cannot: a key file is still a
 * key file when it is encrypted, base64-encoded, or empty because the real one is gitignored and
 * someone committed the template.
 */
@Component
public class HygieneRules implements AnalysisRule {

  /** Files that are a credential by definition, whatever they contain. */
  private static final Set<String> CREDENTIAL_FILE_NAMES = Set.of(
      "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519",
      ".netrc", "_netrc", ".pgpass", "credentials", "service-account.json");

  private static final Set<String> CREDENTIAL_EXTENSIONS = Set.of(
      "pem", "key", "p12", "pfx", "jks", "keystore", "ppk");

  /**
   * Environment files.
   *
   * <p>{@code .env.example} is excluded deliberately: a tracked example with placeholder values is
   * good practice, and flagging it teaches people the rule is wrong.
   */
  private static final Pattern ENV_FILE = Pattern.compile(
      "^\\.env(?!\\.(?:example|sample|template|dist)$)(\\..+)?$");

  /** A conflict marker at the start of a line. Git writes exactly seven characters. */
  private static final Pattern CONFLICT_MARKER =
      Pattern.compile("^(?:<{7}|={7}|>{7})(?:\\s|$)");

  @Value("${devforge.review.large-file-bytes:5242880}")
  private long largeFileBytes;

  @Override
  public List<Finding> analyse(RepositoryFile file) {
    var findings = new ArrayList<Finding>();
    var name = file.fileName().toLowerCase(Locale.ROOT);

    if (CREDENTIAL_FILE_NAMES.contains(name)
        || CREDENTIAL_EXTENSIONS.contains(file.extension())
        || ENV_FILE.matcher(name).matches()) {
      findings.add(new Finding(
          "CREDENTIAL_FILE_COMMITTED",
          Severity.BLOCKER,
          FindingCategory.CREDENTIAL_FILE,
          file.path(),
          null,
          "A file of this kind normally holds credentials and should not be committed. If it was "
              + "ever real, treat the credential as compromised and rotate it — removing the file "
              + "does not remove it from the history.",
          null));
    }

    if (file.size() > largeFileBytes) {
      findings.add(new Finding(
          "LARGE_FILE",
          Severity.LOW,
          FindingCategory.MAINTAINABILITY,
          file.path(),
          null,
          "This file is " + (file.size() / 1_048_576) + " MB. Large binaries in git are kept for "
              + "ever by every clone; consider storing it outside the repository.",
          null));
    }

    // Line-based checks stop here for anything that is not text.
    if (file.binary()) {
      return findings;
    }

    var lines = file.lines();
    for (int i = 0; i < lines.size(); i++) {
      if (CONFLICT_MARKER.matcher(lines.get(i)).find()) {
        findings.add(new Finding(
            "MERGE_CONFLICT_MARKER",
            Severity.HIGH,
            FindingCategory.MERGE_CONFLICT,
            file.path(),
            i + 1,
            "A merge conflict marker was committed. The file almost certainly does not compile or "
                + "run, and whichever side was meant to win has not been chosen.",
            Finding.snippetOf(lines.get(i), 120)));
        // One per file: a conflict produces three markers and they are one problem.
        break;
      }
    }
    return findings;
  }
}
