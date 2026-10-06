package com.devforge.ai.documentationservice.generate;

import com.devforge.ai.common.git.RepositoryFile;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * What this repository is: languages, size, structure and build tooling.
 *
 * <p>The first thing anyone needs when they open an unfamiliar repository, and the one a human
 * README most often fails to keep current — because it is derived from the files rather than
 * written by hand, it cannot go stale while the code changes.
 */
@Component
public class OverviewGenerator implements DocumentGenerator {

  /** Extension → language, for the extensions worth naming. */
  private static final Map<String, String> LANGUAGES = Map.ofEntries(
      Map.entry("java", "Java"),
      Map.entry("kt", "Kotlin"),
      Map.entry("ts", "TypeScript"),
      Map.entry("tsx", "TypeScript (React)"),
      Map.entry("js", "JavaScript"),
      Map.entry("jsx", "JavaScript (React)"),
      Map.entry("py", "Python"),
      Map.entry("go", "Go"),
      Map.entry("rs", "Rust"),
      Map.entry("rb", "Ruby"),
      Map.entry("php", "PHP"),
      Map.entry("cs", "C#"),
      Map.entry("c", "C"),
      Map.entry("h", "C/C++ header"),
      Map.entry("cpp", "C++"),
      Map.entry("sql", "SQL"),
      Map.entry("sh", "Shell"),
      Map.entry("yml", "YAML"),
      Map.entry("yaml", "YAML"),
      Map.entry("json", "JSON"),
      Map.entry("md", "Markdown"),
      Map.entry("css", "CSS"),
      Map.entry("html", "HTML"));

  /** Files whose presence says how the project is built or run. */
  private static final Map<String, String> TOOLING = Map.ofEntries(
      Map.entry("pom.xml", "Maven"),
      Map.entry("build.gradle", "Gradle"),
      Map.entry("build.gradle.kts", "Gradle (Kotlin DSL)"),
      Map.entry("package.json", "npm / Node.js"),
      Map.entry("pnpm-lock.yaml", "pnpm"),
      Map.entry("yarn.lock", "Yarn"),
      Map.entry("requirements.txt", "pip"),
      Map.entry("pyproject.toml", "Python (PEP 518)"),
      Map.entry("go.mod", "Go modules"),
      Map.entry("cargo.toml", "Cargo"),
      Map.entry("gemfile", "Bundler"),
      Map.entry("dockerfile", "Docker"),
      Map.entry("docker-compose.yml", "Docker Compose"),
      Map.entry("makefile", "Make"));

  @Override
  public DocumentKind kind() {
    return DocumentKind.OVERVIEW;
  }

  @Override
  public String title() {
    return "Repository overview";
  }

  @Override
  public String generate(List<RepositoryFile> files) {
    if (files.isEmpty()) {
      return null;
    }

    var out = new StringBuilder();
    out.append("# Repository overview\n\n");
    out.append("_Generated from the files in this repository. Nothing here is inferred._\n\n");

    // ------------------------------------------------------------- summary
    var textFiles = files.stream().filter(f -> !f.binary()).toList();
    long totalLines = textFiles.stream().mapToLong(f -> f.lines().size()).sum();
    long totalBytes = files.stream().mapToLong(RepositoryFile::size).sum();

    out.append("## At a glance\n\n");
    out.append("| | |\n|---|---|\n");
    out.append("| Files scanned | ").append(files.size()).append(" |\n");
    out.append("| Text files | ").append(textFiles.size()).append(" |\n");
    out.append("| Binary files | ").append(files.size() - textFiles.size()).append(" |\n");
    out.append("| Lines of text | ").append(totalLines).append(" |\n");
    out.append("| Total size | ").append(humanBytes(totalBytes)).append(" |\n\n");

    // ----------------------------------------------------------- languages
    var byLanguage = new TreeMap<String, long[]>();
    for (var file : files) {
      var language = LANGUAGES.get(file.extension());
      if (language == null) {
        continue;
      }
      var entry = byLanguage.computeIfAbsent(language, k -> new long[2]);
      entry[0]++;
      entry[1] += file.binary() ? 0 : file.lines().size();
    }

    if (byLanguage.isEmpty()) {
      out.append("## Languages\n\nNo files with a recognised extension were found.\n\n");
    } else {
      out.append("## Languages\n\n| Language | Files | Lines |\n|---|---|---|\n");
      byLanguage.entrySet().stream()
          // Most lines first: that is the better proxy for where the work is.
          .sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[1]).reversed())
          .forEach(e -> out.append("| ").append(e.getKey())
              .append(" | ").append(e.getValue()[0])
              .append(" | ").append(e.getValue()[1]).append(" |\n"));
      out.append("\n");
    }

    // ------------------------------------------------------------ tooling
    var tools = new java.util.TreeSet<String>();
    for (var file : files) {
      var tool = TOOLING.get(file.fileName().toLowerCase(Locale.ROOT));
      if (tool != null) {
        tools.add(tool);
      }
      // Dockerfile.anything is still a Dockerfile.
      if (file.fileName().toLowerCase(Locale.ROOT).startsWith("dockerfile")) {
        tools.add("Docker");
      }
    }
    out.append("## Build and tooling\n\n");
    if (tools.isEmpty()) {
      out.append("No build tooling was recognised among the files scanned.\n\n");
    } else {
      tools.forEach(t -> out.append("- ").append(t).append("\n"));
      out.append("\n");
    }

    // ---------------------------------------------------------- structure
    var topLevel = new LinkedHashMap<String, Integer>();
    for (var file : files) {
      var slash = file.path().indexOf('/');
      var key = slash < 0 ? "(repository root)" : file.path().substring(0, slash) + "/";
      topLevel.merge(key, 1, Integer::sum);
    }
    out.append("## Structure\n\n| Directory | Files |\n|---|---|\n");
    topLevel.entrySet().stream()
        .sorted(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed())
        .limit(20)
        .forEach(e -> out.append("| `").append(e.getKey()).append("` | ")
            .append(e.getValue()).append(" |\n"));
    out.append("\n");

    // ------------------------------------------------------------- readme
    var readme = files.stream()
        .filter(f -> f.fileName().toLowerCase(Locale.ROOT).startsWith("readme"))
        .filter(f -> !f.binary())
        .findFirst();

    out.append("## README\n\n");
    if (readme.isEmpty()) {
      // Stated rather than omitted: "no README" is information, and a silent gap reads as an
      // oversight in the generator instead of a fact about the repository.
      out.append("This repository has no README in the files scanned.\n");
    } else {
      var headings = readme.get().lines().stream()
          .filter(line -> line.startsWith("#"))
          .limit(15)
          .toList();
      out.append("`").append(readme.get().path()).append("`");
      if (headings.isEmpty()) {
        out.append(" exists but contains no Markdown headings.\n");
      } else {
        out.append(" covers:\n\n");
        headings.forEach(h -> out.append("- ").append(h.replaceAll("^#+\\s*", "")).append("\n"));
      }
    }

    return out.toString();
  }

  private static String humanBytes(long bytes) {
    if (bytes < 1024) {
      return bytes + " B";
    }
    if (bytes < 1024 * 1024) {
      return "%.1f KB".formatted(bytes / 1024.0);
    }
    return "%.1f MB".formatted(bytes / (1024.0 * 1024));
  }
}
