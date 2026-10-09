package com.devforge.ai.gitservice.git;

import com.devforge.ai.common.exception.ResourceConflictException;
import com.devforge.ai.common.exception.ResourceNotFoundException;
import com.devforge.ai.gitservice.dto.GitDtos.BlobResponse;
import com.devforge.ai.gitservice.dto.GitDtos.BranchResponse;
import com.devforge.ai.gitservice.dto.GitDtos.CommitResponse;
import com.devforge.ai.gitservice.dto.GitDtos.DiffEntryResponse;
import com.devforge.ai.gitservice.dto.GitDtos.TreeEntryResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Real git operations, through JGit.
 *
 * <p>Repositories are **bare**: there is no working tree, because nothing here checks files out.
 * A commit is built by inserting objects directly, which is both faster and avoids the class of bug
 * where two concurrent requests fight over one checkout.
 *
 * <p>Every method takes an already-validated path or ref — validation belongs to {@link GitPaths}
 * and happens at the service boundary, so this class can assume its inputs are safe. It does not
 * re-validate, because two places doing the same check tend to drift apart.
 */
@Slf4j
@Component
public class GitOperations {

  /** Above this, a blob is returned truncated rather than loaded whole into memory. */
  private final int maxBlobBytes;

  public GitOperations(@Value("${devforge.git.max-blob-bytes:1048576}") int maxBlobBytes) {
    this.maxBlobBytes = maxBlobBytes;
  }

  /** Creates a bare repository with {@code HEAD} pointing at the given branch. */
  public void init(Path directory, String defaultBranch) throws IOException, GitAPIException {
    try (var git = Git.init()
        .setBare(true)
        .setGitDir(directory.toFile())
        .setInitialBranch(defaultBranch)
        .call()) {
      log.debug("Initialised bare repository at {} with default branch {}", directory, defaultBranch);
    }
  }

  public boolean isEmpty(Path directory) {
    try (var repository = open(directory)) {
      return resolveHead(repository) == null;
    } catch (IOException ex) {
      // An unreadable repository is reported as empty rather than failing the list endpoint: one
      // damaged repository must not make the whole project's list unusable.
      log.warn("Could not read repository at {}: {}", directory, ex.getMessage());
      return true;
    }
  }

  public List<BranchResponse> branches(Path directory, String defaultBranch) throws IOException {
    try (var repository = open(directory); var git = new Git(repository)) {
      var result = new ArrayList<BranchResponse>();
      for (var ref : git.branchList().setListMode(ListBranchCommand.ListMode.ALL).call()) {
        var name = Repository.shortenRefName(ref.getName());
        result.add(new BranchResponse(name, ref.getObjectId().getName(), name.equals(defaultBranch)));
      }
      return result;
    } catch (GitAPIException ex) {
      throw new IOException("Could not list branches", ex);
    }
  }

  /** Commit history reachable from {@code ref}, newest first. */
  public List<CommitResponse> commits(Path directory, String ref, int skip, int limit)
      throws IOException {
    try (var repository = open(directory)) {
      var start = resolve(repository, ref);
      var result = new ArrayList<CommitResponse>();
      try (var walk = new RevWalk(repository)) {
        walk.markStart(walk.parseCommit(start));
        int seen = 0;
        for (var commit : walk) {
          if (seen++ < skip) {
            continue;
          }
          result.add(toCommitResponse(commit));
          if (result.size() >= limit) {
            break;
          }
        }
      }
      return result;
    }
  }

  /** One directory level, not recursive: a browse should not load an entire repository. */
  public List<TreeEntryResponse> tree(Path directory, String ref, String path) throws IOException {
    try (var repository = open(directory)) {
      var commit = parse(repository, ref);
      try (var walk = new TreeWalk(repository)) {
        walk.addTree(commit.getTree());
        walk.setRecursive(false);
        if (!path.isEmpty()) {
          walk.setFilter(PathFilter.create(path));
          // Descend to the requested directory. Without this the filter matches the entry itself
          // rather than listing what is inside it.
          var found = false;
          while (walk.next()) {
            if (walk.getPathString().equals(path)) {
              if (!walk.isSubtree()) {
                throw new ResourceNotFoundException("Path is a file, not a directory: " + path);
              }
              walk.enterSubtree();
              found = true;
              break;
            }
            if (walk.isSubtree()) {
              walk.enterSubtree();
            }
          }
          if (!found) {
            throw new ResourceNotFoundException("Path not found: " + path);
          }
          walk.setFilter(org.eclipse.jgit.treewalk.filter.TreeFilter.ALL);
        }

        var entries = new ArrayList<TreeEntryResponse>();
        while (walk.next()) {
          var isDirectory = walk.isSubtree();
          Long size = null;
          if (!isDirectory) {
            size = repository.getObjectDatabase().open(walk.getObjectId(0)).getSize();
          }
          entries.add(new TreeEntryResponse(
              walk.getNameString(),
              walk.getPathString(),
              isDirectory ? "DIRECTORY" : "FILE",
              size));
        }
        // Directories first, then alphabetical — the ordering every file browser uses, and git's
        // own tree order is not it.
        entries.sort((a, b) -> {
          if (a.type().equals(b.type())) {
            return a.name().compareToIgnoreCase(b.name());
          }
          return a.type().equals("DIRECTORY") ? -1 : 1;
        });
        return entries;
      }
    }
  }

  public BlobResponse blob(Path directory, String ref, String path) throws IOException {
    if (path.isEmpty()) {
      throw new IllegalArgumentException("A file path is required");
    }
    try (var repository = open(directory)) {
      var commit = parse(repository, ref);
      try (var walk = TreeWalk.forPath(repository, path, commit.getTree())) {
        if (walk == null) {
          throw new ResourceNotFoundException("File not found: " + path);
        }
        if (walk.isSubtree()) {
          throw new ResourceNotFoundException("Path is a directory, not a file: " + path);
        }

        var loader = repository.getObjectDatabase().open(walk.getObjectId(0));
        var size = loader.getSize();
        var truncated = size > maxBlobBytes;
        var limit = (int) Math.min(size, maxBlobBytes);

        byte[] bytes;
        try (var in = loader.openStream(); var out = new ByteArrayOutputStream(limit)) {
          var buffer = new byte[8192];
          int total = 0;
          int read;
          while (total < limit && (read = in.read(buffer, 0, Math.min(buffer.length, limit - total))) > 0) {
            out.write(buffer, 0, read);
            total += read;
          }
          bytes = out.toByteArray();
        }

        var text = decodeUtf8(bytes, truncated);
        // A binary file returns no content rather than replacement characters: mangled text looks
        // like the file itself is corrupt, which sends the reader after the wrong problem.
        return new BlobResponse(path, size, text == null, truncated, text);
      }
    }
  }

  /**
   * Commits one file, creating or replacing it.
   *
   * <p>Builds the tree from the parent commit plus this one change and inserts the objects directly.
   * The alternative — a working tree, a checkout and an add — would need a lock per repository to be
   * safe, and would put a checkout of every repository on the service's disk.
   *
   * @return the new commit id.
   */
  public String commitFile(
      Path directory, String branch, String path, String content, String message,
      String authorName, String authorEmail) throws IOException {
    return commitChanges(directory, branch, null, List.of(new FileChange(path, content)), message,
        authorName, authorEmail);
  }

  /** One file in a commit: the new content, or {@code null} to delete the file. */
  public record FileChange(String path, String content) {
    boolean isDelete() {
      return content == null;
    }
  }

  /**
   * Writes several file changes as one commit, so an edit spanning files lands atomically.
   *
   * <p>{@code expectedHead}, when given, is the commit the editor loaded. If the branch has moved
   * since, the commit is refused rather than written on top: the user's copy of the other files
   * is stale, and committing would silently revert whatever arrived in between.
   */
  public String commitChanges(
      Path directory, String branch, String expectedHead, List<FileChange> changes,
      String message, String authorName, String authorEmail) throws IOException {

    var byPath = new java.util.LinkedHashMap<String, FileChange>();
    for (var change : changes) {
      if (byPath.put(change.path(), change) != null) {
        throw new IllegalArgumentException("The same file appears twice: " + change.path());
      }
    }

    try (var repository = open(directory)) {
      var branchRef = Constants.R_HEADS + branch;
      var parentId = repository.resolve(branchRef);

      if (expectedHead != null && (parentId == null || !parentId.getName().equals(expectedHead))) {
        throw new ResourceConflictException(
            "The branch has new commits since you started editing. Reload and apply your changes again.");
      }

      try (var inserter = repository.newObjectInserter()) {
        var cache = DirCache.newInCore();
        var builder = cache.builder();
        var existing = new java.util.HashMap<String, FileMode>();
        ObjectId parentTree = null;

        if (parentId != null) {
          // The RevWalk must be closed too. Leaked, it keeps an ObjectReader — and therefore open
          // file handles — alive, which on Windows makes the repository directory undeletable. That
          // surfaced as a delete that reported success while leaving the objects on disk.
          try (var walk = new TreeWalk(repository); var revWalk = new RevWalk(repository)) {
            parentTree = revWalk.parseCommit(parentId).getTree().getId();
            walk.addTree(parentTree);
            walk.setRecursive(true);
            while (walk.next()) {
              var path = walk.getPathString();
              existing.put(path, walk.getFileMode(0));
              if (byPath.containsKey(path)) {
                continue; // replaced or deleted below
              }
              var entry = new DirCacheEntry(path);
              entry.setFileMode(walk.getFileMode(0));
              entry.setObjectId(walk.getObjectId(0));
              builder.add(entry);
            }
          }
        }

        var finalPaths = new java.util.TreeSet<>(existing.keySet());
        for (var change : byPath.values()) {
          if (change.isDelete()) {
            if (!existing.containsKey(change.path())) {
              throw new ResourceNotFoundException("No such file to delete: " + change.path());
            }
            finalPaths.remove(change.path());
          } else {
            finalPaths.add(change.path());
          }
        }

        for (var change : byPath.values()) {
          if (change.isDelete()) {
            continue;
          }
          requireNoFileDirectoryClash(change.path(), finalPaths);
          var entry = new DirCacheEntry(change.path());
          // Keep an executable bit an existing file had; a new file is a regular file.
          var mode = existing.get(change.path());
          entry.setFileMode(mode == FileMode.EXECUTABLE_FILE ? mode : FileMode.REGULAR_FILE);
          entry.setObjectId(inserter.insert(
              Constants.OBJ_BLOB, change.content().getBytes(StandardCharsets.UTF_8)));
          builder.add(entry);
        }
        builder.finish();

        var treeId = cache.writeTree(inserter);
        if (treeId.equals(parentTree)) {
          // An empty commit is noise in the history and would announce a change that did not happen.
          throw new IllegalArgumentException("Nothing to commit: the files already have this content");
        }

        var now = Instant.now();
        var identity = new PersonIdent(authorName, authorEmail, java.util.Date.from(now),
            java.util.TimeZone.getDefault());

        var commit = new CommitBuilder();
        commit.setTreeId(treeId);
        commit.setAuthor(identity);
        commit.setCommitter(identity);
        commit.setMessage(message);
        if (parentId != null) {
          commit.setParentId(parentId);
        }

        var commitId = inserter.insert(commit);
        inserter.flush();

        // A first commit expects the branch not to exist (zeroId), so two first commits racing on
        // an empty repository cannot both succeed with one silently lost.
        updateRef(repository, branchRef, parentId != null ? parentId : ObjectId.zeroId(), commitId,
            "commit");
        return commitId.getName();
      }
    }
  }

  /**
   * A path cannot be both a file and a directory. Writing {@code a/b} while {@code a} remains a
   * file, or {@code a} while {@code a/...} files remain, would produce a tree git itself rejects.
   */
  private static void requireNoFileDirectoryClash(String path, java.util.NavigableSet<String> finalPaths) {
    for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1)) {
      if (finalPaths.contains(path.substring(0, slash))) {
        throw new IllegalArgumentException(
            path.substring(0, slash) + " is a file, so " + path + " cannot be created inside it");
      }
    }
    // Everything under "a/" sorts immediately from "a/" onward, so the first entry there decides it.
    var nested = finalPaths.ceiling(path + "/");
    if (nested != null && nested.startsWith(path + "/")) {
      throw new IllegalArgumentException(path + " is a directory, so it cannot also be a file");
    }
  }

  public String createBranch(Path directory, String name, String fromRef) throws IOException {
    try (var repository = open(directory)) {
      if (repository.resolve(Constants.R_HEADS + name) != null) {
        throw new ResourceConflictException("Branch already exists: " + name);
      }
      var startId = resolve(repository, fromRef);
      updateRef(repository, Constants.R_HEADS + name, null, startId, "branch");
      return startId.getName();
    }
  }

  public List<DiffEntryResponse> diff(Path directory, String from, String to) throws IOException {
    try (var repository = open(directory)) {
      var fromCommit = parse(repository, from);
      var toCommit = parse(repository, to);

      try (var formatter = new DiffFormatter(DisabledOutputStream.INSTANCE);
          var reader = repository.newObjectReader()) {
        formatter.setRepository(repository);
        // Detects a rename as a rename rather than as a delete plus an add, which is what a reader
        // of the diff actually wants to see.
        formatter.setDetectRenames(true);

        var oldTree = new CanonicalTreeParser();
        oldTree.reset(reader, fromCommit.getTree());
        var newTree = new CanonicalTreeParser();
        newTree.reset(reader, toCommit.getTree());

        var result = new ArrayList<DiffEntryResponse>();
        for (DiffEntry entry : formatter.scan(oldTree, newTree)) {
          int added = 0;
          int deleted = 0;
          for (Edit edit : formatter.toFileHeader(entry).toEditList()) {
            added += edit.getEndB() - edit.getBeginB();
            deleted += edit.getEndA() - edit.getBeginA();
          }
          result.add(new DiffEntryResponse(
              entry.getChangeType().name(),
              DiffEntry.DEV_NULL.equals(entry.getOldPath()) ? null : entry.getOldPath(),
              DiffEntry.DEV_NULL.equals(entry.getNewPath()) ? null : entry.getNewPath(),
              added,
              deleted));
        }
        return result;
      }
    }
  }

  /**
   * What merging {@code source} into {@code target} would do, without doing it.
   *
   * @param mergeBase the commit the two branches last shared; a pull request's diff is taken from
   *     here, so it shows what the source adds rather than everything the target gained since
   * @param alreadyMerged the source is already contained in the target: nothing to merge
   * @param conflicts paths both sides changed incompatibly; empty when the merge is clean
   */
  public record MergePreview(
      String targetHead, String sourceHead, String mergeBase, boolean alreadyMerged,
      List<String> conflicts) {}

  public MergePreview previewMerge(Path directory, String target, String source) throws IOException {
    try (var repository = open(directory); var walk = new RevWalk(repository)) {
      var targetCommit = walk.parseCommit(resolveBranch(repository, target));
      var sourceCommit = walk.parseCommit(resolveBranch(repository, source));
      var base = mergeBase(repository, targetCommit, sourceCommit);
      var alreadyMerged = walk.isMergedInto(sourceCommit, targetCommit);

      var conflicts = List.<String>of();
      if (!alreadyMerged) {
        var merger = (org.eclipse.jgit.merge.ResolveMerger)
            org.eclipse.jgit.merge.MergeStrategy.RECURSIVE.newMerger(repository, true);
        if (!merger.merge(targetCommit, sourceCommit)) {
          conflicts = merger.getUnmergedPaths().stream().sorted().toList();
          if (conflicts.isEmpty()) {
            // A failure with no conflicting path is a structural one, e.g. a file replaced by a
            // directory on one side. Still a conflict, not a mergeable change.
            conflicts = List.of("(the branches cannot be merged automatically)");
          }
        }
      }
      return new MergePreview(targetCommit.getName(), sourceCommit.getName(),
          base == null ? null : base.getName(), alreadyMerged, conflicts);
    }
  }

  /**
   * Merges {@code source} into {@code target} with a merge commit, entirely in memory.
   *
   * <p>A bare repository has no working tree, so the merge runs in core and the result is a tree
   * plus a commit with both heads as parents. Always a merge commit, never a fast-forward: the
   * history then records that a reviewed change landed, and where.
   *
   * <p>{@code expectedSourceHead} is the source commit the reviewer saw. If the branch has moved
   * since, the merge is refused: approving one change must not merge a different one. The target
   * is updated with compare-and-swap for the same reason in the other direction.
   */
  public String merge(
      Path directory, String target, String source, String expectedSourceHead, String message,
      String authorName, String authorEmail) throws IOException {

    try (var repository = open(directory); var walk = new RevWalk(repository)) {
      var targetCommit = walk.parseCommit(resolveBranch(repository, target));
      var sourceCommit = walk.parseCommit(resolveBranch(repository, source));

      if (expectedSourceHead != null && !sourceCommit.getName().equals(expectedSourceHead)) {
        throw new ResourceConflictException(
            "The source branch has new commits since this pull request was reviewed. Review them first.");
      }
      if (walk.isMergedInto(sourceCommit, targetCommit)) {
        throw new ResourceConflictException("Nothing to merge: the target already contains these commits");
      }

      var merger = (org.eclipse.jgit.merge.ResolveMerger)
          org.eclipse.jgit.merge.MergeStrategy.RECURSIVE.newMerger(repository, true);
      if (!merger.merge(targetCommit, sourceCommit)) {
        var paths = merger.getUnmergedPaths().stream().sorted().toList();
        throw new ResourceConflictException(paths.isEmpty()
            ? "The branches cannot be merged automatically"
            : "Merge conflicts in: " + String.join(", ", paths));
      }

      try (var inserter = repository.newObjectInserter()) {
        var identity = new PersonIdent(authorName, authorEmail, java.util.Date.from(Instant.now()),
            java.util.TimeZone.getDefault());
        var commit = new CommitBuilder();
        commit.setTreeId(merger.getResultTreeId());
        commit.setParentIds(targetCommit, sourceCommit);
        commit.setAuthor(identity);
        commit.setCommitter(identity);
        commit.setMessage(message);
        var commitId = inserter.insert(commit);
        inserter.flush();

        updateRef(repository, Constants.R_HEADS + target, targetCommit, commitId, "merge");
        return commitId.getName();
      }
    }
  }

  private static RevCommit mergeBase(Repository repository, RevCommit a, RevCommit b)
      throws IOException {
    try (var walk = new RevWalk(repository)) {
      walk.setRevFilter(org.eclipse.jgit.revwalk.filter.RevFilter.MERGE_BASE);
      walk.markStart(walk.parseCommit(a));
      walk.markStart(walk.parseCommit(b));
      return walk.next();
    }
  }

  /** A branch, and only a branch: a pull request between arbitrary commits means nothing. */
  private ObjectId resolveBranch(Repository repository, String branch) throws IOException {
    var id = repository.resolve(Constants.R_HEADS + branch);
    if (id == null) {
      throw new ResourceNotFoundException("Branch not found: " + branch);
    }
    return id;
  }

  // ---------------------------------------------------------------- internals

  private Repository open(Path directory) throws IOException {
    if (!directory.toFile().isDirectory()) {
      // The metadata row exists but the objects do not. Reported as not-found rather than as a
      // server error, because the caller can do nothing about it either way and a 500 would page
      // somebody for one broken repository.
      throw new ResourceNotFoundException("Repository storage is missing");
    }
    return new FileRepositoryBuilder().setGitDir(directory.toFile()).setMustExist(true).build();
  }

  private ObjectId resolveHead(Repository repository) throws IOException {
    return repository.resolve(Constants.HEAD);
  }

  private ObjectId resolve(Repository repository, String ref) throws IOException {
    // Branch first, so a branch called "abc1234" is not shadowed by an object id that happens to
    // match. Explicit about which namespace is searched rather than relying on git's own rules.
    var id = repository.resolve(Constants.R_HEADS + ref);
    if (id == null) {
      id = repository.resolve(ref);
    }
    if (id == null) {
      throw new ResourceNotFoundException("Ref not found: " + ref);
    }
    return id;
  }

  private RevCommit parse(Repository repository, String ref) throws IOException {
    try (var walk = new RevWalk(repository)) {
      return walk.parseCommit(resolve(repository, ref));
    }
  }

  private void updateRef(
      Repository repository, String ref, ObjectId expectedOld, ObjectId newId, String what)
      throws IOException {
    var update = repository.updateRef(ref);
    update.setNewObjectId(newId);
    if (expectedOld != null) {
      // Compare-and-swap. Two concurrent commits to the same branch would otherwise let the second
      // overwrite the first's commit, losing it silently.
      update.setExpectedOldObjectId(expectedOld);
    }
    update.setRefLogMessage("devforge " + what, false);

    var result = update.update();
    switch (result) {
      case NEW, FAST_FORWARD, FORCED, NO_CHANGE -> { }
      case LOCK_FAILURE, REJECTED -> throw new ResourceConflictException(
          "The branch moved while this change was being written. Retry.");
      default -> throw new IOException("Could not update " + ref + ": " + result);
    }
  }

  private CommitResponse toCommitResponse(RevCommit commit) {
    var parents = new ArrayList<String>(commit.getParentCount());
    for (var parent : commit.getParents()) {
      parents.add(parent.getId().getName());
    }
    var author = commit.getAuthorIdent();
    return new CommitResponse(
        commit.getId().getName(),
        commit.getId().abbreviate(7).name(),
        commit.getFullMessage(),
        author == null ? null : author.getName(),
        author == null ? null : author.getEmailAddress(),
        Instant.ofEpochSecond(commit.getCommitTime()),
        parents);
  }

  /**
   * Decodes as UTF-8, or returns null if the bytes are not valid UTF-8.
   *
   * <p>Uses a strict decoder rather than {@code new String(bytes, UTF_8)}, which silently substitutes
   * replacement characters and so would report every binary file as text.
   */
  private static String decodeUtf8(byte[] bytes, boolean truncated) {
    // A NUL byte is the practical signal for binary content, and valid UTF-8 text in a source file
    // does not contain one.
    for (var b : bytes) {
      if (b == 0) {
        return null;
      }
    }

    // A truncated file may have been cut mid-character, which strict decoding rejects — so a large
    // text file would be reported as binary purely because of where the read limit fell. A UTF-8
    // sequence is at most four bytes, so dropping up to three trailing bytes is enough to land on a
    // boundary if the content is text at all.
    var maxDropped = truncated ? 3 : 0;
    for (int dropped = 0; dropped <= maxDropped && dropped < bytes.length; dropped++) {
      var decoder = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT);
      try {
        return decoder
            .decode(java.nio.ByteBuffer.wrap(bytes, 0, bytes.length - dropped))
            .toString();
      } catch (CharacterCodingException ex) {
        // Try one fewer byte, or give up and report it as binary.
      }
    }
    return null;
  }
}
