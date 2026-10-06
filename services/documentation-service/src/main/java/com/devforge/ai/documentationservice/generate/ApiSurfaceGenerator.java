package com.devforge.ai.documentationservice.generate;

import com.devforge.ai.common.git.RepositoryFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The HTTP endpoints actually present in the source.
 *
 * <p>Found by matching the route declarations of three common frameworks. This is deliberately a
 * text scan rather than a parse: a parser per language is a large amount of machinery for a
 * document that is useful at this level of detail, and a scan degrades honestly — it finds less
 * rather than inventing more.
 *
 * <p>Its limits are stated in the generated document itself, because a reader who believes this is
 * exhaustive will conclude an endpoint does not exist when it merely was not matched. That is the
 * failure mode worth guarding against: a confident, wrong document is worse than none.
 */
@Component
public class ApiSurfaceGenerator implements DocumentGenerator {

  private record Framework(String name, Set<String> extensions, Pattern route, Pattern prefix) {}

  private static final List<Framework> FRAMEWORKS = List.of(
      new Framework(
          "Spring",
          Set.of("java", "kt"),
          // @GetMapping("/x"), @RequestMapping(value = "/x", ...), and bare @GetMapping.
          //
          // The path is optional on purpose. A bare @GetMapping is the ordinary way to declare the
          // collection endpoint of a resource — `GET /users` on a controller mapped to /users — and
          // requiring a string argument missed every one of them, which is the single most common
          // endpoint in a REST API.
          Pattern.compile(
              "@(Get|Post|Put|Patch|Delete|Request)Mapping\\b"
                  + "(?:\\s*\\(\\s*(?:value\\s*=\\s*)?[\"']([^\"']*)[\"'])?"),
          // A class-level @RequestMapping is the prefix every method in it inherits.
          Pattern.compile("@RequestMapping\\s*\\(\\s*(?:value\\s*=\\s*)?[\"']([^\"']*)[\"']")),
      new Framework(
          "Express",
          Set.of("js", "ts", "mjs", "cjs"),
          Pattern.compile(
              "\\b(?:app|router)\\s*\\.\\s*(get|post|put|patch|delete)\\s*\\(\\s*[\"'`]([^\"'`]+)[\"'`]"),
          null),
      new Framework(
          "FastAPI / Flask",
          Set.of("py"),
          Pattern.compile(
              "@(?:app|router)\\.(get|post|put|patch|delete|route)\\s*\\(\\s*[\"']([^\"']+)[\"']"),
          null));

  @Override
  public DocumentKind kind() {
    return DocumentKind.API_SURFACE;
  }

  @Override
  public String title() {
    return "API surface";
  }

  @Override
  public String generate(List<RepositoryFile> files) {
    // Framework → path → methods+location, sorted so the document is stable between runs.
    var byFramework = new TreeMap<String, TreeMap<String, List<String>>>();
    var scanned = 0;

    for (var file : files) {
      if (file.binary()) {
        continue;
      }
      for (var framework : FRAMEWORKS) {
        if (!framework.extensions().contains(file.extension())) {
          continue;
        }
        scanned++;
        var prefix = classPrefix(file, framework);
        var matcher = framework.route().matcher(file.content());

        while (matcher.find()) {
          var method = matcher.group(1).toUpperCase(Locale.ROOT);
          // Null when the annotation carried no path, e.g. a bare @GetMapping. That means "the
          // mapping of the enclosing class", so it resolves to the prefix.
          var path = matcher.group(2) == null ? "" : matcher.group(2);

          // A class-level @RequestMapping declares the prefix; it is not an endpoint of its own.
          if ("REQUEST".equals(method) && (path.isEmpty() || path.equals(prefix))) {
            continue;
          }
          var full = join(prefix, path);
          byFramework
              .computeIfAbsent(framework.name(), k -> new TreeMap<>())
              .computeIfAbsent(full, k -> new ArrayList<>())
              .add(("REQUEST".equals(method) ? "ANY" : method) + " — `" + file.path() + "`");
        }
        break;
      }
    }

    if (byFramework.isEmpty()) {
      if (scanned == 0) {
        return null;
      }
      return """
          # API surface

          No HTTP route declarations were found in the %d source file(s) scanned.

          This scan recognises Spring (`@GetMapping` and friends), Express (`app.get(...)`) and
          FastAPI/Flask (`@app.get(...)`). A repository using a different framework, or declaring
          routes dynamically, will show nothing here even though it serves HTTP.
          """
          .formatted(scanned);
    }

    var out = new StringBuilder();
    out.append("# API surface\n\n");
    out.append("_Found by scanning route declarations in the source. ")
        .append("See the limits at the end before concluding an endpoint does not exist._\n\n");

    for (var framework : byFramework.entrySet()) {
      out.append("## ").append(framework.getKey()).append("\n\n");
      out.append("| Method | Path | Declared in |\n|---|---|---|\n");
      for (var route : framework.getValue().entrySet()) {
        for (var entry : route.getValue()) {
          var split = entry.split(" — ", 2);
          out.append("| ").append(split[0])
              .append(" | `").append(route.getKey()).append("` | ")
              .append(split[1]).append(" |\n");
        }
      }
      out.append("\n");
    }

    out.append("""
        ## Limits of this scan

        - It matches route **declarations in text**, not a parsed program. Routes registered at
          runtime, built from variables, or added by a framework convention are not found.
        - Path variables appear exactly as written in the source, including their names.
        - A class-level prefix is applied where one is declared in the same file. A prefix inherited
          from a base class in another file is not resolved, so such a path will be incomplete.
        - Only Spring, Express and FastAPI/Flask are recognised.

        Treat an absence here as "not found by this scan", never as "does not exist".
        """);

    return out.toString();
  }

  /** The class-level prefix, where the framework has one and the file declares it. */
  private String classPrefix(RepositoryFile file, Framework framework) {
    if (framework.prefix() == null) {
      return "";
    }
    var matcher = framework.prefix().matcher(file.content());
    return matcher.find() ? matcher.group(1) : "";
  }

  private static String join(String prefix, String path) {
    if (prefix == null || prefix.isEmpty()) {
      return path.isEmpty() ? "/" : path;
    }
    if (path.isEmpty()) {
      return prefix;
    }
    var left = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
    var right = path.startsWith("/") ? path : "/" + path;
    return left + right;
  }
}
