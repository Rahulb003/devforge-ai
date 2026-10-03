package com.devforge.ai.gitservice;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Date;
import java.util.TimeZone;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * Writes content the API itself cannot produce.
 *
 * <p>The commit endpoint takes text in JSON, so a genuinely binary file — one containing a NUL byte —
 * cannot be created through it. Testing how binary content is served therefore needs a commit made
 * with JGit directly, which is also closer to how such a file would really arrive: pushed, not typed.
 */
final class GitTestFixtures {

  private GitTestFixtures() {}

  /** Commits raw bytes to {@code branch}, preserving whatever is already there. */
  static void commitBytes(Path gitDir, String branch, String path, byte[] content)
      throws IOException {
    try (var repository = new FileRepositoryBuilder()
        .setGitDir(gitDir.toFile())
        .setMustExist(true)
        .build()) {

      var branchRef = Constants.R_HEADS + branch;
      var parentId = repository.resolve(branchRef);

      try (var inserter = repository.newObjectInserter()) {
        var blobId = inserter.insert(Constants.OBJ_BLOB, content);

        var cache = DirCache.newInCore();
        var builder = cache.builder();

        if (parentId != null) {
          try (var walk = new TreeWalk(repository); var revWalk = new RevWalk(repository)) {
            walk.addTree(revWalk.parseCommit(parentId).getTree());
            walk.setRecursive(true);
            while (walk.next()) {
              if (walk.getPathString().equals(path)) {
                continue;
              }
              var existing = new DirCacheEntry(walk.getPathString());
              existing.setFileMode(walk.getFileMode(0));
              existing.setObjectId(walk.getObjectId(0));
              builder.add(existing);
            }
          }
        }

        var entry = new DirCacheEntry(path);
        entry.setFileMode(FileMode.REGULAR_FILE);
        entry.setObjectId(blobId);
        builder.add(entry);
        builder.finish();

        var identity = new PersonIdent("Fixture", "fixture@example.com", new Date(), TimeZone.getDefault());
        var commit = new CommitBuilder();
        commit.setTreeId(cache.writeTree(inserter));
        commit.setAuthor(identity);
        commit.setCommitter(identity);
        commit.setMessage("Add " + path);
        if (parentId != null) {
          commit.setParentId(parentId);
        }

        var commitId = inserter.insert(commit);
        inserter.flush();

        var update = repository.updateRef(branchRef);
        update.setNewObjectId(commitId);
        if (parentId != null) {
          update.setExpectedOldObjectId(parentId);
        }
        update.update();
      }
    }
  }
}
