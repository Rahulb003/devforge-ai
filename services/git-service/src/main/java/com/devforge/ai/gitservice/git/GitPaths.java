package com.devforge.ai.gitservice.git;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation for every piece of attacker-supplied text that reaches git or the filesystem.
 *
 * <p>A product that hosts other people's repositories takes repository names, branch names and file
 * paths from untrusted input. Each of those is used to address something — a directory, a ref, an
 * entry in a tree — so each needs validating before it is used, not after.
 *
 * <p>Everything here rejects rather than sanitises. Sanitising invites the classic bypass where
 * stripping {@code ../} from {@code ....//} yields {@code ../}, and a rejected request is a clearer
 * answer to the caller than a silently altered one.
 */
public final class GitPaths {

  /*
   * Failures are IllegalArgumentException, which the shared GlobalExceptionHandler already maps to
   * 400 and whose message it echoes deliberately, because these messages are written for the
   * caller. A new exception type would need a new handler to do the same thing.
   */

  private GitPaths() {}

  /** Repository names: a path segment on disk, so deliberately narrow. */
  private static final Pattern REPOSITORY_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$");

  /** Branch names, within what git itself permits. */
  private static final Pattern BRANCH_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/-]{0,254}$");

  /** A full 40-character SHA-1, or an abbreviated one of at least 7. */
  private static final Pattern OBJECT_ID = Pattern.compile("^[0-9a-fA-F]{7,40}$");

  /**
   * Names Windows refuses to use as a file, whatever the extension.
   *
   * <p>Checked because the service may run on Windows — it does during development — where a
   * repository called {@code CON} cannot be created and the failure is obscure.
   */
  private static final Set<String> RESERVED_NAMES = Set.of(
      "con", "prn", "aux", "nul",
      "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
      "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

  /** A path that must not escape the repository, and must not be a git internal. */
  public static String requireSafeRepositoryPath(String path) {
    if (path == null || path.isBlank()) {
      // The repository root, which is a legitimate thing to list.
      return "";
    }
    var normalised = path.replace('\\', '/').trim();
    while (normalised.startsWith("/")) {
      normalised = normalised.substring(1);
    }

    if (normalised.length() > 1024) {
      throw new IllegalArgumentException("Path is too long");
    }
    // A NUL byte truncates the string in some native calls, so a path that passes validation can
    // address a different file by the time it is used.
    if (normalised.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("Path contains an illegal character");
    }
    if (normalised.contains("//")) {
      throw new IllegalArgumentException("Path contains an empty segment");
    }

    for (var segment : normalised.split("/")) {
      if (segment.equals("..")) {
        throw new IllegalArgumentException("Path must not traverse outside the repository");
      }
      if (segment.equals(".")) {
        throw new IllegalArgumentException("Path must not contain '.' segments");
      }
      // .git inside a repository path is how a tree entry would reach the object database.
      if (segment.equalsIgnoreCase(".git")) {
        throw new IllegalArgumentException("Path must not reference git internals");
      }
    }
    // An absolute Windows path survives the checks above: "C:/secrets" has no traversal and no
    // empty segment, but a drive letter means it is not relative to anything.
    if (normalised.matches("^[A-Za-z]:.*")) {
      throw new IllegalArgumentException("Path must be relative to the repository root");
    }
    return normalised;
  }

  public static String requireValidRepositoryName(String name) {
    if (name == null || !REPOSITORY_NAME.matcher(name).matches()) {
      throw new IllegalArgumentException(
          "Repository name must start with a letter or digit and contain only letters, digits, "
              + "dots, underscores and hyphens");
    }
    if (name.equals(".") || name.equals("..")) {
      throw new IllegalArgumentException("Repository name is not allowed");
    }
    var stem = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
    if (RESERVED_NAMES.contains(stem.toLowerCase(Locale.ROOT))) {
      throw new IllegalArgumentException("Repository name is reserved by the operating system");
    }
    return name;
  }

  /**
   * A ref the caller may resolve: a branch name or an object id.
   *
   * <p>Rejects the forms git itself treats specially — {@code ..} ranges, {@code @{}} reflog
   * syntax, leading hyphens that a command-line parser would read as a flag — even though JGit is
   * being called as a library rather than as a command. The validation is here so that remains true
   * if any part of this ever does shell out.
   */
  public static String requireValidRef(String ref) {
    if (ref == null || ref.isBlank()) {
      throw new IllegalArgumentException("A ref is required");
    }
    // Checked before trimming, because String.trim() removes every character at or below U+0020 —
    // including NUL. So "main\0" would arrive here, be quietly trimmed to "main", and pass as a
    // valid ref. Stripping it is safe but silently accepting malformed input is not a habit worth
    // keeping, and a caller sending a NUL deserves to be told.
    for (int i = 0; i < ref.length(); i++) {
      var c = ref.charAt(i);
      if (c < 0x20 || c == 0x7f) {
        throw new IllegalArgumentException("Ref contains a control character");
      }
    }
    var trimmed = ref.trim();
    if (trimmed.length() > 255) {
      throw new IllegalArgumentException("Ref is too long");
    }
    if (OBJECT_ID.matcher(trimmed).matches()) {
      return trimmed;
    }
    if (trimmed.startsWith("-")) {
      throw new IllegalArgumentException("Ref must not start with a hyphen");
    }
    if (trimmed.contains("..") || trimmed.contains("@{") || trimmed.contains("^") || trimmed.contains("~")
        || trimmed.contains(":") || trimmed.contains("?") || trimmed.contains("*")
        || trimmed.contains("[") || trimmed.contains("\\") || trimmed.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("Ref contains characters that are not allowed");
    }
    if (!BRANCH_NAME.matcher(trimmed).matches()) {
      throw new IllegalArgumentException("Ref is not a valid branch name or object id");
    }
    if (trimmed.endsWith("/") || trimmed.endsWith(".") || trimmed.endsWith(".lock")) {
      throw new IllegalArgumentException("Ref is not a valid branch name");
    }
    return trimmed;
  }

  public static String requireValidBranchName(String branch) {
    var ref = requireValidRef(branch);
    if (OBJECT_ID.matcher(ref).matches()) {
      throw new IllegalArgumentException("A branch name cannot look like an object id");
    }
    return ref;
  }
}
