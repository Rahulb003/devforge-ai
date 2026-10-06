package com.devforge.ai.documentationservice.generate;

import static org.assertj.core.api.Assertions.assertThat;

import com.devforge.ai.common.git.RepositoryFile;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The generators, on realistic file content.
 *
 * <p>The recurring assertion across all three: a generator must never state something it did not
 * find. A document that confidently describes an endpoint which does not exist is worse than no
 * document, because the reader has no way to tell.
 */
@DisplayName("Document generators")
class GeneratorTest {

  private static RepositoryFile file(String path, String content) {
    return RepositoryFile.of(path, content.length(), false, false, content);
  }

  private static RepositoryFile binary(String path, long size) {
    return RepositoryFile.of(path, size, false, true, "");
  }

  @Nested
  @DisplayName("OverviewGenerator")
  class Overview {

    private final OverviewGenerator generator = new OverviewGenerator();

    @Test
    @DisplayName("counts languages by lines, not just by file count")
    void countsLanguages() {
      var doc = generator.generate(List.of(
          file("src/App.java", "class App {\n  void run() {}\n}\n"),
          file("src/Util.java", "class Util {}\n"),
          file("web/app.ts", "export const x = 1;\n")));

      assertThat(doc).contains("| Java | 2 |");
      assertThat(doc).contains("TypeScript");
      assertThat(doc).contains("Files scanned");
    }

    @Test
    @DisplayName("names the build tooling it recognises")
    void detectsTooling() {
      var doc = generator.generate(List.of(
          file("pom.xml", "<project/>"),
          file("frontend/package.json", "{}"),
          file("Dockerfile", "FROM eclipse-temurin:21"),
          file("src/App.java", "class App {}")));

      assertThat(doc).contains("Maven");
      assertThat(doc).contains("npm / Node.js");
      assertThat(doc).contains("Docker");
    }

    @Test
    @DisplayName("says so when there is no README, rather than omitting the section")
    void statesAMissingReadme() {
      var doc = generator.generate(List.of(file("src/App.java", "class App {}")));

      // A silent gap reads as a bug in the generator instead of a fact about the repository.
      assertThat(doc).contains("## README");
      assertThat(doc).contains("has no README");
    }

    @Test
    @DisplayName("lists the README's headings when there is one")
    void listsReadmeHeadings() {
      var doc = generator.generate(List.of(
          file("README.md", "# Payments API\n\n## Getting started\n\ntext\n\n## Deployment\n"),
          file("src/App.java", "class App {}")));

      assertThat(doc).contains("Getting started");
      assertThat(doc).contains("Deployment");
    }

    @Test
    @DisplayName("counts a binary file without trying to read it")
    void handlesBinaries() {
      var doc = generator.generate(List.of(
          binary("assets/logo.png", 4096),
          file("src/App.java", "class App {}")));

      assertThat(doc).contains("| Binary files | 1 |");
    }

    @Test
    @DisplayName("produces nothing for an empty repository")
    void emptyRepository() {
      // No document at all, rather than a document that says nothing: a reader cannot tell an
      // empty document from a failed one.
      assertThat(generator.generate(List.of())).isNull();
    }
  }

  @Nested
  @DisplayName("ApiSurfaceGenerator")
  class ApiSurface {

    private final ApiSurfaceGenerator generator = new ApiSurfaceGenerator();

    @Test
    @DisplayName("finds Spring endpoints and applies the class-level prefix")
    void findsSpringEndpoints() {
      var doc = generator.generate(List.of(file("src/UserController.java", """
          @RestController
          @RequestMapping("/api/v1/users")
          public class UserController {

            @GetMapping
            public List<User> list() { return List.of(); }

            @GetMapping("/{id}")
            public User get(@PathVariable UUID id) { return null; }

            @PostMapping("/{id}/activate")
            public void activate(@PathVariable UUID id) {}

            @DeleteMapping("/{id}")
            public void delete(@PathVariable UUID id) {}
          }
          """)));

      assertThat(doc).contains("Spring");
      assertThat(doc).contains("`/api/v1/users`");
      assertThat(doc).contains("`/api/v1/users/{id}`");
      assertThat(doc).contains("`/api/v1/users/{id}/activate`");
      // The bare @GetMapping - the collection endpoint - is the one a path-requiring pattern missed.
      assertThat(doc).contains("| GET | `/api/v1/users` |");
      assertThat(doc).contains("GET");
      assertThat(doc).contains("POST");
      assertThat(doc).contains("DELETE");
      // The class-level mapping is the prefix, not an endpoint in its own right.
      assertThat(doc).doesNotContain("| ANY |");
    }

    @Test
    @DisplayName("finds Express routes")
    void findsExpressRoutes() {
      var doc = generator.generate(List.of(file("server/routes.js", """
          app.get('/health', (req, res) => res.send('ok'));
          router.post("/users", createUser);
          router.delete(`/users/:id`, deleteUser);
          """)));

      assertThat(doc).contains("Express");
      assertThat(doc).contains("`/health`");
      assertThat(doc).contains("`/users`");
      assertThat(doc).contains("`/users/:id`");
    }

    @Test
    @DisplayName("finds FastAPI routes")
    void findsFastApiRoutes() {
      var doc = generator.generate(List.of(file("app/main.py", """
          @app.get("/items")
          def list_items():
              return []

          @app.post("/items/{item_id}")
          def create(item_id: str):
              return None
          """)));

      assertThat(doc).contains("FastAPI");
      assertThat(doc).contains("`/items`");
      assertThat(doc).contains("`/items/{item_id}`");
    }

    @Test
    @DisplayName("states its own limits, so an absence is not read as proof")
    void statesItsLimits() {
      var doc = generator.generate(List.of(file("src/UserController.java", """
          @RestController
          @RequestMapping("/api/v1/users")
          public class UserController {
            @GetMapping
            public List<User> list() { return List.of(); }
          }
          """)));

      // The failure mode this guards against: a reader concluding an endpoint does not exist when
      // it merely was not matched.
      assertThat(doc).contains("Limits of this scan");
      assertThat(doc).contains("never as \"does not exist\"");
    }

    @Test
    @DisplayName("says it found nothing when a source file declares no routes")
    void noRoutesFound() {
      var doc = generator.generate(List.of(file("src/Util.java", "class Util { int add() {} }")));

      assertThat(doc).contains("No HTTP route declarations were found");
      // And explains why that might be wrong, rather than implying the repository serves nothing.
      assertThat(doc).contains("different framework");
    }

    @Test
    @DisplayName("produces nothing when there is no source to scan")
    void noSourceAtAll() {
      assertThat(generator.generate(List.of(file("README.md", "# hi")))).isNull();
    }
  }

  @Nested
  @DisplayName("DocCoverageGenerator")
  class Coverage {

    private final DocCoverageGenerator generator = new DocCoverageGenerator();

    @Test
    @DisplayName("counts documented and undocumented public declarations")
    void countsCoverage() {
      var doc = generator.generate(List.of(file("src/Service.java", """
          public class Service {

            /** Does the thing. */
            public void documented() {}

            public void undocumented() {}
          }
          """)));

      assertThat(doc).contains("| Public declarations | 3 |");
      assertThat(doc).contains("Undocumented");
      assertThat(doc).contains("`undocumented`");
      // The documented one must not appear in the to-do list.
      assertThat(doc).doesNotContain("`documented` |");
    }

    @Test
    @DisplayName("looks past annotations to find the doc comment above a declaration")
    void looksPastAnnotations() {
      var doc = generator.generate(List.of(file("src/Controller.java", """
          /** The controller. */
          public class Controller {

            /** Lists everything. */
            @GetMapping
            @ResponseBody
            public List<String> list() { return List.of(); }
          }
          """)));

      // A declaration separated from its Javadoc by annotations is still documented; counting it
      // as missing would make the number meaningless on any Spring codebase.
      //
      // The class carries its own Javadoc here deliberately: the class is a public declaration too,
      // and leaving it undocumented would make this assert 50% for a reason unrelated to what it
      // is testing.
      assertThat(doc).contains("| Public declarations | 2 |");
      assertThat(doc).contains("| Coverage | **100%** |");
    }

    @Test
    @DisplayName("finds a Python docstring, which comes after the declaration")
    void pythonDocstringsComeAfter() {
      var doc = generator.generate(List.of(file("app/service.py", """
          def documented():
              \"\"\"Does the thing.\"\"\"
              return 1

          def undocumented():
              return 2
          """)));

      assertThat(doc).contains("| Public declarations | 2 |");
      assertThat(doc).contains("`undocumented`");
      assertThat(doc).doesNotContain("| `documented` |");
    }

    @Test
    @DisplayName("counts exported TypeScript declarations")
    void countsTypeScriptExports() {
      var doc = generator.generate(List.of(file("src/api.ts", """
          /** Fetches a user. */
          export function getUser(id: string) {}

          export interface User { id: string }
          """)));

      assertThat(doc).contains("| Public declarations | 2 |");
      assertThat(doc).contains("`User`");
    }

    @Test
    @DisplayName("reports the location of each undocumented declaration")
    void reportsLocations() {
      var doc = generator.generate(List.of(file("src/Service.java", """
          public class Service {
            public void first() {}
          }
          """)));

      // A list of things to do, not a score to improve.
      assertThat(doc).contains("src/Service.java:2");
    }

    @Test
    @DisplayName("explains what counts as documented, so the number can be judged")
    void explainsTheMeasure() {
      var doc = generator.generate(List.of(file("src/Service.java", "public class Service {}")));

      assertThat(doc).contains("What counts as documented");
      assertThat(doc).contains("floor rather than a measure of quality");
    }

    @Test
    @DisplayName("produces nothing when there are no public declarations")
    void nothingToMeasure() {
      assertThat(generator.generate(List.of(file("README.md", "# hi")))).isNull();
    }
  }
}
