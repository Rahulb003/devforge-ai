package com.devforge.ai.reviewservice.analysis;

import com.devforge.ai.common.git.RepositoryFile;
import static org.assertj.core.api.Assertions.assertThat;

import com.devforge.ai.reviewservice.model.ReviewModel.FindingCategory;
import com.devforge.ai.reviewservice.model.ReviewModel.Severity;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Secret detection, and the two things that decide whether anyone keeps it switched on: it must
 * catch real credentials, and it must not shout about placeholders.
 *
 * <p>Every credential in this file is syntactically shaped like the real thing and functionally
 * worthless — they exist to exercise the patterns.
 */
@DisplayName("SecretRules")
class SecretRulesTest {

  private final SecretRules rules = new SecretRules();

  private List<Finding> analyse(String path, String content) {
    return rules.analyse(RepositoryFile.of(path, content.length(), false, false, content));
  }

  @Nested
  @DisplayName("detects")
  class Detects {

    @ParameterizedTest
    @ValueSource(strings = {
      "aws_access_key_id = AKIAIOSFODNN7EXAMPLX",
      "String key = \"ASIAIOSFODNN7EXAMPLX\";",
      "GITHUB_TOKEN=ghp_aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789",
      "token: xoxb-1234567890-abcdefghijkl",
      "const k = 'AIzaSyA1B2C3D4E5F6G7H8I9J0K1L2M3N4O5P6Q';",
      "stripe.key = sk_live_aBcDeFgHiJkLmNoPqRs",
      "DATABASE_URL=postgres://admin:s3cr3tpassword@db.internal:5432/app",
      "password = \"hunter2hunter2\"",
      "api_key: \"8f14e45fceea167a5a36dedd4bea2543\"",
      "client_secret = 'Zx9kLm2PqR7sT4vW'",
    })
    @DisplayName("credentials, as BLOCKER")
    void detectsCredentials(String line) {
      var findings = analyse("src/config.java", line);

      assertThat(findings).hasSize(1);
      assertThat(findings.get(0).severity()).isEqualTo(Severity.BLOCKER);
      assertThat(findings.get(0).category()).isEqualTo(FindingCategory.SECRET);
      assertThat(findings.get(0).lineNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("a private key block")
    void detectsPrivateKey() {
      var findings = analyse("deploy/key", """
          -----BEGIN RSA PRIVATE KEY-----
          MIIEowIBAAKCAQEA3Tz2mr7SZiAMfQyuvBjM9Oi
          -----END RSA PRIVATE KEY-----
          """);

      assertThat(findings).hasSize(1);
      assertThat(findings.get(0).ruleId()).isEqualTo("SECRET_PRIVATE_KEY");
      assertThat(findings.get(0).lineNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("the right line number in a longer file")
    void reportsLineNumber() {
      var findings = analyse("app.py", """
          import os

          def connect():
              password = "realLookingSecret123"
              return password
          """);

      assertThat(findings).hasSize(1);
      assertThat(findings.get(0).lineNumber()).isEqualTo(4);
    }

    @Test
    @DisplayName("one finding per line, even when two patterns match it")
    void oneFindingPerLine() {
      // Both the URL-credentials and assigned-literal patterns match this. It is still one line to
      // fix, and two findings would double-count it in the gate.
      var findings = analyse("config.yml",
          "password = \"postgres://admin:s3cr3tpassword@db.internal:5432/app\"");

      assertThat(findings).hasSize(1);
    }
  }

  @Nested
  @DisplayName("stays quiet about")
  class StaysQuiet {

    @ParameterizedTest
    @ValueSource(strings = {
      "password = \"change-me-please\"",
      "api_key = \"your-api-key-here\"",
      "SECRET=${APP_SECRET}",
      "password: <your password>",
      "token = \"EXAMPLE_TOKEN_VALUE\"",
      "api_key = \"placeholder-value-here\"",
      "password = \"****************\"",
      "secret = \"REDACTED\"",
      "password = \"not-a-real-password\"",
      "api_key = \"sample-key-for-docs\"",
    })
    @DisplayName("placeholders, because a rule people ignore protects nothing")
    void ignoresPlaceholders(String line) {
      assertThat(analyse("README.md", line)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
      "password = userInput",
      "password = \"\"",
      "password = request.getParameter(\"password\")",
      "const password = props.password;",
      "String PASSWORD_FIELD = \"password\";",
    })
    @DisplayName("references to a credential that are not a credential")
    void ignoresNonLiterals(String line) {
      // The pattern requires a quoted value of some length, so a variable, an empty string or a
      // field name does not match.
      assertThat(analyse("src/Login.java", line)).isEmpty();
    }

    @Test
    @DisplayName("but a commented-out credential IS still reported")
    void commentedCredentialIsStillACredential() {
      // Deliberately not excluded. Unlike the dangerous-pattern rules, where a construct named in
      // a comment is discussion rather than code, a credential in a comment is a real credential
      // sitting in the file and in the history. Commenting it out protects nothing.
      var findings = analyse("src/Login.java", "// password = \"hunter2hunter2\" was removed");

      assertThat(findings).hasSize(1);
      assertThat(findings.get(0).severity()).isEqualTo(Severity.BLOCKER);
    }

    @Test
    @DisplayName("hashes and ids, which an entropy heuristic would flag")
    void ignoresHighEntropyNonSecrets() {
      var findings = analyse("src/data.ts", """
          const commit = 'a3f5c8e9b2d1470e8f6a9c3b5d7e1f2a4b6c8d0e';
          const id = '550e8400-e29b-41d4-a716-446655440000';
          const hash = 'sha256-47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=';
          """);

      // This is why the patterns are anchored on vendor prefixes rather than on entropy: all three
      // of these are high-entropy and none is a secret.
      assertThat(findings).isEmpty();
    }

    @Test
    @DisplayName("binary content")
    void skipsBinary() {
      var file = RepositoryFile.of("logo.png", 1024, false, true, "");
      assertThat(rules.analyse(file)).isEmpty();
    }
  }

  @Nested
  @DisplayName("redaction")
  class Redaction {

    @Test
    @DisplayName("never quotes the credential it found")
    void redactsTheSecret() {
      var secret = "AKIAIOSFODNN7EXAMPLX";
      var findings = analyse("config.tf", "access_key = \"" + secret + "\"");

      assertThat(findings).hasSize(1);
      var snippet = findings.get(0).snippet();

      // The finding is stored, returned by the API and written to logs. Quoting the secret would
      // create three new copies of it, so a detection would become a leak.
      assertThat(snippet).doesNotContain(secret);
      assertThat(snippet).contains("[REDACTED");
      // Enough of a hint to recognise which key to rotate, not enough to use.
      assertThat(snippet).contains("AKIA");
      assertThat(snippet).contains("access_key");
    }

    @Test
    @DisplayName("redacts a password inside a URL")
    void redactsUrlCredentials() {
      var findings = analyse(".env.local", "DATABASE_URL=postgres://admin:s3cr3tpassword@db:5432/app");

      assertThat(findings).hasSize(1);
      assertThat(findings.get(0).snippet()).doesNotContain("s3cr3tpassword");
    }

    @Test
    @DisplayName("keeps a snippet short enough for a UI")
    void truncatesLongLines() {
      var findings = analyse("bundle.js",
          "x".repeat(2000) + " password = \"realLookingSecret123\" " + "y".repeat(2000));

      assertThat(findings).hasSize(1);
      assertThat(findings.get(0).snippet().length()).isLessThanOrEqualTo(300);
    }
  }
}
