package com.devforge.ai.gitservice.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Validation of attacker-supplied git input, tested directly.
 *
 * <p>The API tests cover these through HTTP, which is what matters; this class exists because the
 * rules are dense enough that a failure is far easier to diagnose here, and because a few cases —
 * control characters, length limits — are awkward to send through a JSON request.
 */
@DisplayName("GitPaths")
class GitPathsTest {

  @Nested
  @DisplayName("repository paths")
  class RepositoryPaths {

    @ParameterizedTest
    @ValueSource(strings = {
      "README.md",
      "src/main/java/App.java",
      "a-b_c.1/d.txt",
      "deeply/nested/but/fine/file.ts",
      "git-hub.md",         // contains "git" but is not ".git"
      "src/.gitignore",     // a dotfile, which is not the .git directory
    })
    @DisplayName("accepts ordinary paths")
    void acceptsOrdinaryPaths(String path) {
      assertThat(GitPaths.requireSafeRepositoryPath(path)).isEqualTo(path);
    }

    @Test
    @DisplayName("treats a blank path as the repository root")
    void blankIsRoot() {
      assertThat(GitPaths.requireSafeRepositoryPath(null)).isEmpty();
      assertThat(GitPaths.requireSafeRepositoryPath("")).isEmpty();
      assertThat(GitPaths.requireSafeRepositoryPath("  ")).isEmpty();
    }

    @Test
    @DisplayName("strips a leading slash rather than rejecting it")
    void stripsLeadingSlash() {
      // "/src/App.java" is how a UI commonly addresses a repository-relative path, and it is
      // unambiguous, so rejecting it would be pedantry rather than safety.
      assertThat(GitPaths.requireSafeRepositoryPath("/src/App.java")).isEqualTo("src/App.java");
    }

    @Test
    @DisplayName("normalises backslashes, which Windows clients send")
    void normalisesBackslashes() {
      assertThat(GitPaths.requireSafeRepositoryPath("src\\main\\App.java"))
          .isEqualTo("src/main/App.java");
    }

    @ParameterizedTest
    @ValueSource(strings = {
      "../etc/passwd",
      "..",
      "a/../../b",
      "a/..",
      "....//etc/passwd",   // defeats a sanitiser that strips "../" once
      "./a",
      "a/./b",
      "a//b",
      ".git/config",
      "a/.git/config",
      ".GIT/HEAD",          // case-insensitive, because the filesystem may be too
      "C:/Windows/System32",
      "c:/secrets.txt",
    })
    @DisplayName("rejects traversal, git internals and absolute paths")
    void rejectsHostilePaths(String path) {
      assertThatThrownBy(() -> GitPaths.requireSafeRepositoryPath(path))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects a NUL byte, which can truncate a path in a native call")
    void rejectsNulByte() {
      assertThatThrownBy(() -> GitPaths.requireSafeRepositoryPath("ok.txt\u0000.png"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects an unreasonably long path")
    void rejectsLongPath() {
      assertThatThrownBy(() -> GitPaths.requireSafeRepositoryPath("a/".repeat(600) + "f.txt"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("too long");
    }
  }

  @Nested
  @DisplayName("repository names")
  class RepositoryNames {

    @ParameterizedTest
    @ValueSource(strings = {"api", "payments-api", "my_repo", "repo.v2", "a", "A1"})
    @DisplayName("accepts ordinary names")
    void acceptsOrdinaryNames(String name) {
      assertThat(GitPaths.requireValidRepositoryName(name)).isEqualTo(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {
      "../escape", "..", ".", ".hidden", "-leading", "with space", "a/b", "a\\b",
      "tab\there", "quote\"here", "semi;colon", "amp&and", "dollar$sign", "back`tick",
      "con", "CON", "nul", "com1", "LPT3", "aux.txt",
    })
    @DisplayName("rejects names that are unsafe as a directory or reserved by the OS")
    void rejectsHostileNames(String name) {
      assertThatThrownBy(() -> GitPaths.requireValidRepositoryName(name))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects a name longer than the column allows")
    void rejectsLongName() {
      assertThatThrownBy(() -> GitPaths.requireValidRepositoryName("a".repeat(101)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("refs")
  class Refs {

    @ParameterizedTest
    @ValueSource(strings = {
      "main", "develop", "feature/login", "release/1.2.3", "a_b-c", "v1.0",
      "abc1234",                                   // abbreviated object id
      "0123456789abcdef0123456789abcdef01234567",  // full object id
    })
    @DisplayName("accepts branch names and object ids")
    void acceptsValidRefs(String ref) {
      assertThat(GitPaths.requireValidRef(ref)).isEqualTo(ref);
    }

    @ParameterizedTest
    @ValueSource(strings = {
      "-x",
      "--upload-pack=touch /tmp/pwned",
      "HEAD@{1}",
      "main^",
      "main~2",
      "main..other",
      "refs:other",
      "main?",
      "main*",
      "main[1]",
      "main\\x",
      "main/",
      "main.",
      "main.lock",
      "",
      "   ",
    })
    @DisplayName("rejects git's special syntax and anything a flag parser would misread")
    void rejectsHostileRefs(String ref) {
      assertThatThrownBy(() -> GitPaths.requireValidRef(ref))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects a branch name that would be ambiguous with an object id")
    void branchCannotLookLikeAnObjectId() {
      // Creating a branch called "abc1234" makes every later reference to it ambiguous.
      assertThatThrownBy(() -> GitPaths.requireValidBranchName("abc1234"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(GitPaths.requireValidBranchName("abc123z")).isEqualTo("abc123z");
    }
  }
}
