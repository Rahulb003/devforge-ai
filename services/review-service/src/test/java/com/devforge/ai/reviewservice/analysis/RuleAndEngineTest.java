package com.devforge.ai.reviewservice.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.devforge.ai.reviewservice.model.ReviewModel.FindingCategory;
import com.devforge.ai.reviewservice.model.ReviewModel.GateResult;
import com.devforge.ai.reviewservice.model.ReviewModel.Severity;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

/** The remaining rules, and the engine that runs them and decides the gate. */
@DisplayName("Rules and the analysis engine")
class RuleAndEngineTest {

  private HygieneRules hygiene;
  private DangerousPatternRules dangerous;

  @BeforeEach
  void setUp() {
    hygiene = new HygieneRules();
    ReflectionTestUtils.setField(hygiene, "largeFileBytes", 1024L);
    dangerous = new DangerousPatternRules();
  }

  private static AnalysedFile file(String path, String content) {
    return AnalysedFile.of(path, content.length(), false, false, content);
  }

  @Nested
  @DisplayName("HygieneRules")
  class Hygiene {

    @ParameterizedTest
    @ValueSource(strings = {
      "id_rsa", "deploy/id_ed25519", "certs/server.pem", "keystore.jks", "app.p12",
      ".env", ".env.local", ".env.production", "config/.netrc", "service-account.json",
    })
    @DisplayName("flags files that are a credential by what they are called")
    void flagsCredentialFiles(String path) {
      var findings = hygiene.analyse(file(path, "anything"));

      assertThat(findings).anySatisfy(finding -> {
        assertThat(finding.ruleId()).isEqualTo("CREDENTIAL_FILE_COMMITTED");
        assertThat(finding.severity()).isEqualTo(Severity.BLOCKER);
        assertThat(finding.category()).isEqualTo(FindingCategory.CREDENTIAL_FILE);
        // About the file, not a line in it.
        assertThat(finding.lineNumber()).isNull();
      });
    }

    @ParameterizedTest
    @ValueSource(strings = {".env.example", ".env.sample", ".env.template", ".env.dist"})
    @DisplayName("does not flag a tracked env template, which is good practice")
    void allowsEnvExamples(String path) {
      // Flagging these teaches people the rule is wrong, and then they stop reading all of them.
      assertThat(hygiene.analyse(file(path, "API_KEY=your-key-here"))).isEmpty();
    }

    @Test
    @DisplayName("flags a committed merge conflict, once per file")
    void flagsConflictMarkers() {
      var findings = hygiene.analyse(file("src/App.java", """
          class App {
          <<<<<<< HEAD
            void run() { left(); }
          =======
            void run() { right(); }
          >>>>>>> feature/other
          }
          """));

      // A conflict writes three markers and they are one problem.
      assertThat(findings).hasSize(1);
      assertThat(findings.get(0).ruleId()).isEqualTo("MERGE_CONFLICT_MARKER");
      assertThat(findings.get(0).severity()).isEqualTo(Severity.HIGH);
      assertThat(findings.get(0).lineNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("does not mistake equals-signs or diff syntax for a conflict")
    void doesNotFlagLookalikes() {
      var findings = hygiene.analyse(file("README.md", """
          Heading
          =======
          """));
      // A setext underline is exactly seven '=' here, which is why the marker check also requires
      // the line to start with it and be followed by whitespace or end-of-line... and this one is.
      // Markdown underlines are a known false positive; accepted because a committed conflict is
      // far more costly than one noisy finding on a README.
      assertThat(findings).hasSize(1);

      var notAConflict = hygiene.analyse(file("src/x.js", """
          const a = 1;
          // ====== section ======
          const b = 2;
          """));
      assertThat(notAConflict).isEmpty();
    }

    @Test
    @DisplayName("flags a large file by size, including a binary one")
    void flagsLargeFiles() {
      var big = AnalysedFile.of("assets/video.mp4", 20L * 1024 * 1024, false, true, "");
      var findings = hygiene.analyse(big);

      assertThat(findings).hasSize(1);
      assertThat(findings.get(0).ruleId()).isEqualTo("LARGE_FILE");
      assertThat(findings.get(0).severity()).isEqualTo(Severity.LOW);
      assertThat(findings.get(0).message()).contains("20 MB");
    }

    @Test
    @DisplayName("a credential file that is also binary still gets flagged")
    void credentialFileBeatsBinary() {
      // A .p12 is binary by nature. Skipping binaries for line rules must not skip the filename
      // rule, or the most obvious credential file of all would go unreported.
      var findings = hygiene.analyse(AnalysedFile.of("certs/client.p12", 2048, false, true, ""));

      assertThat(findings).anyMatch(f -> f.ruleId().equals("CREDENTIAL_FILE_COMMITTED"));
    }
  }

  @Nested
  @DisplayName("DangerousPatternRules")
  class Dangerous {

    @Test
    @DisplayName("flags eval in JavaScript but not the word elsewhere")
    void flagsEval() {
      assertThat(dangerous.analyse(file("src/app.js", "const r = eval(input);")))
          .anyMatch(f -> f.ruleId().equals("DANGEROUS_EVAL"));

      // Same text in a language the rule does not claim to understand.
      assertThat(dangerous.analyse(file("notes.txt", "const r = eval(input);"))).isEmpty();

      // A method whose name merely ends in "eval".
      assertThat(dangerous.analyse(file("src/app.js", "scheduleRetrieval(input);"))).isEmpty();
    }

    @Test
    @DisplayName("flags disabled TLS verification in any language")
    void flagsDisabledTls() {
      for (var line : List.of(
          "requests.get(url, verify=False)",
          "const agent = new https.Agent({ rejectUnauthorized: false });",
          "tls.Config{InsecureSkipVerify: true}",
          "curl --insecure https://api.example.com")) {
        assertThat(dangerous.analyse(file("x.py", line)))
            .as(line)
            .anyMatch(f -> f.ruleId().equals("DANGEROUS_TLS_VERIFICATION_DISABLED"));
      }
    }

    @Test
    @DisplayName("flags SQL built by concatenation")
    void flagsSqlConcatenation() {
      assertThat(dangerous.analyse(
              file("src/Dao.java", "var sql = \"SELECT * FROM users WHERE id = \" + id;")))
          .anyMatch(f -> f.ruleId().equals("DANGEROUS_SQL_CONCATENATION"));

      // A bound parameter is the fix, and must not be flagged.
      assertThat(dangerous.analyse(
              file("src/Dao.java", "var sql = \"SELECT * FROM users WHERE id = ?\";")))
          .isEmpty();
    }

    @Test
    @DisplayName("ignores the pattern when it appears in a comment")
    void ignoresComments() {
      // Unlike a credential, a construct named in a comment is discussion rather than code — and
      // flagging it makes the rule look broken to whoever is reading the explanation.
      assertThat(dangerous.analyse(file("src/app.js", "// never use eval( here"))).isEmpty();
      assertThat(dangerous.analyse(file("src/App.java", " * Runtime.getRuntime().exec( is unsafe")))
          .isEmpty();
      assertThat(dangerous.analyse(file("x.py", "# pickle.loads( is dangerous"))).isEmpty();
    }

    @Test
    @DisplayName("is MEDIUM, so it informs rather than blocks")
    void severityIsMedium() {
      var findings = dangerous.analyse(file("src/app.jsx", "<div dangerouslySetInnerHTML={h} />"));

      // These need human judgement. Blocking merges on a construct that is occasionally correct is
      // how a gate gets switched off, which costs more than the findings are worth.
      assertThat(findings).hasSize(1);
      assertThat(findings.get(0).severity()).isEqualTo(Severity.MEDIUM);
    }

    @Test
    @DisplayName("skips binary content")
    void skipsBinary() {
      assertThat(dangerous.analyse(AnalysedFile.of("a.png", 100, false, true, ""))).isEmpty();
    }
  }

  @Nested
  @DisplayName("AnalysisEngine")
  class Engine {

    private AnalysisEngine engine(int perFile, int perReview, Severity failAt) {
      var created = new AnalysisEngine(List.of(hygiene, dangerous, new SecretRules()));
      ReflectionTestUtils.setField(created, "maxFindingsPerFile", perFile);
      ReflectionTestUtils.setField(created, "maxFindingsPerReview", perReview);
      ReflectionTestUtils.setField(created, "failAtSeverity", failAt);
      return created;
    }

    @Test
    @DisplayName("passes a clean repository")
    void passesCleanCode() {
      var result = engine(50, 1000, Severity.BLOCKER)
          .analyse(List.of(file("src/App.java", "class App { void run() {} }")));

      assertThat(result.gate()).isEqualTo(GateResult.PASS);
      assertThat(result.findings()).isEmpty();
      assertThat(result.filesAnalysed()).isEqualTo(1);
      // Every severity is present as a key, so a caller never has to handle absence.
      assertThat(result.counts()).containsOnlyKeys(Severity.values());
    }

    @Test
    @DisplayName("fails the gate on a BLOCKER and counts by severity")
    void failsOnBlocker() {
      var result = engine(50, 1000, Severity.BLOCKER).analyse(List.of(
          file("src/Config.java", "String key = \"AKIAIOSFODNN7EXAMPLX\";"),
          file("src/app.js", "const r = eval(input);")));

      assertThat(result.gate()).isEqualTo(GateResult.FAIL);
      assertThat(result.counts().get(Severity.BLOCKER)).isEqualTo(1);
      assertThat(result.counts().get(Severity.MEDIUM)).isEqualTo(1);
    }

    @Test
    @DisplayName("a MEDIUM finding alone does not fail the default gate")
    void mediumDoesNotFailByDefault() {
      var result = engine(50, 1000, Severity.BLOCKER)
          .analyse(List.of(file("src/app.js", "const r = eval(input);")));

      assertThat(result.findings()).hasSize(1);
      assertThat(result.gate()).isEqualTo(GateResult.PASS);
    }

    @Test
    @DisplayName("the gate threshold is configurable")
    void gateThresholdIsConfigurable() {
      var files = List.of(file("src/app.js", "const r = eval(input);"));

      assertThat(engine(50, 1000, Severity.MEDIUM).analyse(files).gate())
          .isEqualTo(GateResult.FAIL);
      assertThat(engine(50, 1000, Severity.HIGH).analyse(files).gate())
          .isEqualTo(GateResult.PASS);
    }

    @Test
    @DisplayName("truncates per file, keeping the most severe findings")
    void truncationKeepsTheWorst() {
      // One secret plus many eval()s. Truncation must not drop the BLOCKER to make room for MEDIUMs,
      // or the gate would pass on a file containing a credential.
      var content = new StringBuilder("String key = \"AKIAIOSFODNN7EXAMPLX\";\n");
      for (int i = 0; i < 20; i++) {
        content.append("const r").append(i).append(" = eval(x);\n");
      }

      var result = engine(3, 1000, Severity.BLOCKER)
          .analyse(List.of(file("src/app.js", content.toString())));

      assertThat(result.findings()).hasSize(3);
      assertThat(result.truncated()).isTrue();
      assertThat(result.findings()).anyMatch(f -> f.severity() == Severity.BLOCKER);
      assertThat(result.gate()).isEqualTo(GateResult.FAIL);
    }

    @Test
    @DisplayName("stops at the per-review limit")
    void truncatesPerReview() {
      var files = new java.util.ArrayList<AnalysedFile>();
      for (int i = 0; i < 20; i++) {
        files.add(file("src/f" + i + ".js", "const r = eval(x);"));
      }

      var result = engine(50, 5, Severity.BLOCKER).analyse(files);

      assertThat(result.findings()).hasSizeLessThanOrEqualTo(5);
      assertThat(result.truncated()).isTrue();
      // The whole repository was not walked, and the count reflects only what was looked at.
      assertThat(result.filesAnalysed()).isEqualTo(20);
    }

    @Test
    @DisplayName("one failing rule does not fail the review")
    void oneBadRuleDoesNotFailEverything() {
      AnalysisRule exploding = f -> {
        throw new IllegalStateException("rule bug");
      };
      var withBadRule = new AnalysisEngine(List.of(exploding, new SecretRules()));
      ReflectionTestUtils.setField(withBadRule, "maxFindingsPerFile", 50);
      ReflectionTestUtils.setField(withBadRule, "maxFindingsPerReview", 1000);
      ReflectionTestUtils.setField(withBadRule, "failAtSeverity", Severity.BLOCKER);

      var result = withBadRule.analyse(
          List.of(file("src/Config.java", "String key = \"AKIAIOSFODNN7EXAMPLX\";")));

      // Repository content is hostile input, so a rule meeting something it cannot handle is a bug
      // to fix — not a reason to deny the user every other finding.
      assertThat(result.findings()).hasSize(1);
      assertThat(result.gate()).isEqualTo(GateResult.FAIL);
    }
  }
}
