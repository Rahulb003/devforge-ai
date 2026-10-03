package com.devforge.ai.gitservice.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Where a repository's git objects live.
 *
 * <p>The path is **derived from ids**, never from anything the caller sent:
 * {@code <root>/<organizationId>/<repositoryId>.git}. A name-based path would make repository
 * creation a filesystem write addressed by user input, and the id is already unique, so there is
 * nothing to gain by using the name. It also means renaming a repository moves no files.
 *
 * <p>Organization-first so one tenant's repositories sit under one directory, which makes a
 * per-tenant quota or a bulk export something the filesystem can express.
 */
@Slf4j
@Component
public class RepositoryStorage {

  private final Path root;

  public RepositoryStorage(@Value("${devforge.git.storage-root}") String storageRoot) {
    // Resolved and normalised once at startup so every later containment check compares against an
    // absolute path. A relative root would be interpreted against the working directory, which
    // differs between running the jar and running the tests.
    this.root = Path.of(storageRoot).toAbsolutePath().normalize();
  }

  public Path root() {
    return root;
  }

  /** The directory for one repository. Does not create it. */
  public Path directoryFor(UUID organizationId, UUID repositoryId) {
    var resolved = root
        .resolve(organizationId.toString())
        .resolve(repositoryId + ".git")
        .normalize();

    // Belt and braces. Both components are UUIDs, so this cannot currently fail — but it is the
    // check that keeps a future change to the naming scheme from becoming a traversal.
    if (!resolved.startsWith(root)) {
      throw new IllegalStateException("Resolved repository path escaped the storage root");
    }
    return resolved;
  }

  public void createDirectories(Path directory) throws IOException {
    Files.createDirectories(directory);
  }

  /**
   * Removes a repository's directory and everything in it.
   *
   * <p>Verifies containment before deleting. A recursive delete driven by a computed path is the
   * kind of operation that turns a path bug into data loss, so the assertion is worth its cost.
   */
  public void deleteRecursively(Path directory) throws IOException {
    var normalised = directory.toAbsolutePath().normalize();
    if (!normalised.startsWith(root) || normalised.equals(root)) {
      throw new IllegalStateException(
          "Refusing to delete a path outside the storage root: " + normalised);
    }
    if (!Files.exists(normalised)) {
      return;
    }
    var failures = new java.util.ArrayList<Path>();
    try (var walk = Files.walk(normalised)) {
      walk.sorted(Comparator.reverseOrder()).forEach(path -> {
        try {
          // Git writes loose object files read-only, to make accidental modification hard. Windows
          // refuses to delete a read-only file, so without this a repository delete half-succeeds
          // there: the row disappears, the objects stay, and nothing says so. Clearing the flag is a
          // no-op where it is already writable.
          var file = path.toFile();
          if (!file.canWrite()) {
            //noinspection ResultOfMethodCallIgnored
            file.setWritable(true);
          }
          Files.delete(path);
        } catch (IOException ex) {
          failures.add(path);
        }
      });
    }

    if (!failures.isEmpty()) {
      // Error, not warn: the metadata row is already gone, so nothing will ever retry this. Left at
      // warn it would be an invisible storage leak that grows with every delete.
      log.error("Deleted the repository record but {} file(s) under {} could not be removed; "
              + "this storage needs manual cleanup. First few: {}",
          failures.size(), normalised,
          failures.stream().limit(5).map(Path::toString).toList());
      throw new IOException(
          "Could not fully delete repository storage: " + failures.size() + " file(s) remain");
    }
  }
}
