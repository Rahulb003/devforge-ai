package com.devforge.ai.documentationservice.generate;

import com.devforge.ai.common.git.RepositoryFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * How much of the public surface carries a doc comment.
 *
 * <p>Reports a number and the specific undocumented declarations behind it, because a coverage
 * percentage on its own is a score to game rather than a list of things to do.
 *
 * <p>Deliberately counts only <em>public</em> declarations. Internal helpers do not need a doc
 * comment, and counting them produces a low number that says nothing — which is how a metric gets
 * ignored.
 */
@Component
public class DocCoverageGenerator implements DocumentGenerator {

  private static final Set<String> JAVA = Set.of("java", "kt");
  private static final Set<String> TS = Set.of("ts", "tsx", "js", "jsx");
  private static final Set<String> PY = Set.of("py");

  /** A public type or method declaration, as the start of a line. */
  private static final Pattern JAVA_PUBLIC = Pattern.compile(
      "^\\s*public\\s+(?:static\\s+|final\\s+|abstract\\s+|synchronized\\s+)*"
          + "(?:class|interface|enum|record|[\\w<>\\[\\],.?\\s]+)\\s+(\\w+)\\s*[({]");

  private static final Pattern TS_EXPORT = Pattern.compile(
      "^\\s*export\\s+(?:default\\s+)?(?:async\\s+)?"
          + "(?:function|class|interface|type|const|enum)\\s+(\\w+)");

  private static final Pattern PY_DEF = Pattern.compile("^(?:def|class)\\s+(\\w+)");

  /** Lines that count as documentation immediately above a declaration. */
  private static final Pattern DOC_LINE = Pattern.compile(
      "^\\s*(?:/\\*\\*|\\*|\\*/|///|//!|#)");

  private record Declaration(String file, int line, String name, boolean documented) {}

  @Override
  public DocumentKind kind() {
    return DocumentKind.DOC_COVERAGE;
  }

  @Override
  public String title() {
    return "Documentation coverage";
  }

  @Override
  public String generate(List<RepositoryFile> files) {
    var declarations = new ArrayList<Declaration>();

    for (var file : files) {
      if (file.binary()) {
        continue;
      }
      Pattern pattern;
      if (JAVA.contains(file.extension())) {
        pattern = JAVA_PUBLIC;
      } else if (TS.contains(file.extension())) {
        pattern = TS_EXPORT;
      } else if (PY.contains(file.extension())) {
        pattern = PY_DEF;
      } else {
        continue;
      }

      var lines = file.lines();
      for (int i = 0; i < lines.size(); i++) {
        var matcher = pattern.matcher(lines.get(i));
        if (!matcher.find()) {
          continue;
        }
        declarations.add(new Declaration(
            file.path(), i + 1, matcher.group(1), isDocumented(lines, i, PY.contains(file.extension()))));
      }
    }

    if (declarations.isEmpty()) {
      return null;
    }

    var documented = declarations.stream().filter(Declaration::documented).count();
    var percentage = Math.round((documented * 100.0) / declarations.size());

    var out = new StringBuilder();
    out.append("# Documentation coverage\n\n");
    out.append("_Counts public declarations only. Internal helpers are excluded: counting them ")
        .append("produces a low number that says nothing._\n\n");

    out.append("## Summary\n\n| | |\n|---|---|\n");
    out.append("| Public declarations | ").append(declarations.size()).append(" |\n");
    out.append("| Documented | ").append(documented).append(" |\n");
    out.append("| Coverage | **").append(percentage).append("%** |\n\n");

    var undocumented = declarations.stream().filter(d -> !d.documented()).toList();
    if (undocumented.isEmpty()) {
      out.append("Every public declaration found carries a doc comment.\n");
      return out.toString();
    }

    out.append("## Undocumented\n\n");
    out.append("The specific declarations behind the number, so this is a list of things to do ")
        .append("rather than a score to improve.\n\n");
    out.append("| Declaration | Location |\n|---|---|\n");
    undocumented.stream()
        .limit(100)
        .forEach(d -> out.append("| `").append(d.name()).append("` | `")
            .append(d.file()).append(":").append(d.line()).append("` |\n"));

    if (undocumented.size() > 100) {
      out.append("\n_").append(undocumented.size() - 100)
          .append(" more not listed._\n");
    }

    out.append("""

        ## What counts as documented

        A declaration counts as documented when the line immediately above it is a comment — a
        Javadoc/JSDoc block, a line comment, or (for Python) a docstring on the following line.
        A comment that merely restates the name satisfies this check but helps nobody, so treat the
        number as a floor rather than a measure of quality.
        """);

    return out.toString();
  }

  /**
   * Whether a declaration carries documentation.
   *
   * <p>Python is handled separately because its docstring comes <em>after</em> the declaration
   * rather than before it — checking the line above would report every documented function as
   * undocumented.
   */
  private static boolean isDocumented(List<String> lines, int index, boolean python) {
    if (python) {
      for (int i = index + 1; i < Math.min(index + 3, lines.size()); i++) {
        var line = lines.get(i).strip();
        if (line.startsWith("\"\"\"") || line.startsWith("'''")) {
          return true;
        }
        if (!line.isEmpty()) {
          return false;
        }
      }
      return false;
    }

    // Walk up past annotations and blank lines to whatever precedes the declaration.
    for (int i = index - 1; i >= 0; i--) {
      var line = lines.get(i).strip();
      if (line.isEmpty() || line.startsWith("@")) {
        continue;
      }
      return DOC_LINE.matcher(line).find();
    }
    return false;
  }
}
