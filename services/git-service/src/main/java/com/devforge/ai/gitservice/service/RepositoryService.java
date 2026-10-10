package com.devforge.ai.gitservice.service;

import com.devforge.ai.common.events.EventTypes;
import com.devforge.ai.common.events.KafkaTopics;
import com.devforge.ai.common.events.outbox.OutboxEventRecorder;
import com.devforge.ai.common.exception.ResourceConflictException;
import com.devforge.ai.common.exception.ResourceNotFoundException;
import com.devforge.ai.common.security.client.ProjectAccessClient;
import com.devforge.ai.gitservice.dto.GitDtos.BlobResponse;
import com.devforge.ai.gitservice.dto.GitDtos.BranchResponse;
import com.devforge.ai.gitservice.dto.GitDtos.CommitChangesRequest;
import com.devforge.ai.gitservice.dto.GitDtos.CommitFileRequest;
import com.devforge.ai.gitservice.dto.GitDtos.CommitResponse;
import com.devforge.ai.gitservice.dto.GitDtos.CreateBranchRequest;
import com.devforge.ai.gitservice.dto.GitDtos.CreateRepositoryRequest;
import com.devforge.ai.gitservice.dto.GitDtos.DiffResponse;
import com.devforge.ai.gitservice.dto.GitDtos.RepositoryResponse;
import com.devforge.ai.gitservice.dto.GitDtos.TreeEntryResponse;
import com.devforge.ai.gitservice.dto.GitDtos.UpdateRepositoryRequest;
import com.devforge.ai.gitservice.entity.RepositoryEntity;
import com.devforge.ai.gitservice.git.GitOperations;
import com.devforge.ai.gitservice.git.GitPaths;
import com.devforge.ai.gitservice.git.RepositoryStorage;
import com.devforge.ai.gitservice.repository.GitRepositoryRepository;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repositories: metadata in the database, objects on disk.
 *
 * <p>The two can disagree, and the ordering here is chosen so that when they do, the harmless case is
 * the one that happens. See {@link #create} and {@link #delete}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepositoryService {

  private static final String DEFAULT_BRANCH = "main";

  private final GitRepositoryRepository repositories;
  private final GitAccessService access;
  private final RepositoryStorage storage;
  private final GitOperations git;
  private final OutboxEventRecorder outbox;

  // --------------------------------------------------------------- metadata

  /**
   * Creates the metadata row and the on-disk repository.
   *
   * <p>The row is written first and the directory second, inside the same transaction. If the
   * filesystem fails the transaction rolls back and neither exists. If the process dies between the
   * two, the result is a row whose storage is missing — which reads as 404 and can be deleted — and
   * that is strictly better than the reverse, an orphaned directory nothing knows about and nothing
   * will ever clean up.
   */
  @Transactional
  public RepositoryResponse create(
      UUID organizationId, UUID projectId, CreateRepositoryRequest request) {

    access.requireProjectAccess(organizationId, projectId, ProjectAccessClient.Access.WRITE);
    var user = access.requireCurrentUser();

    var name = GitPaths.requireValidRepositoryName(request.name());
    var branch = request.initialBranch() == null || request.initialBranch().isBlank()
        ? DEFAULT_BRANCH
        : GitPaths.requireValidBranchName(request.initialBranch());

    // Checked explicitly as well as by the unique index, so the caller gets a clear 409 rather than
    // a constraint-violation surfacing as something less specific.
    if (repositories.existsByProjectIdAndNameIgnoreCase(projectId, name)) {
      throw new ResourceConflictException("A repository called '" + name + "' already exists");
    }

    var entity = repositories.save(RepositoryEntity.builder()
        .projectId(projectId)
        .organizationId(organizationId)
        .name(name)
        .description(request.description())
        .defaultBranch(branch)
        .createdBy(user.id())
        .build());

    var directory = storage.directoryFor(organizationId, entity.getId());
    try {
      storage.createDirectories(directory.getParent());
      git.init(directory, branch);
    } catch (Exception ex) {
      // Rolls the row back with it, so a failed create leaves nothing behind.
      throw new IllegalStateException("Could not initialise the repository on disk", ex);
    }

    outbox.record(
        KafkaTopics.REPOSITORIES,
        EventTypes.REPOSITORY_CREATED,
        organizationId,
        user.id(),
        MDC.get("correlationId"),
        Map.of(
            "repositoryId", entity.getId().toString(),
            "projectId", projectId.toString(),
            "name", name,
            "defaultBranch", branch));

    return RepositoryResponse.from(entity, true);
  }

  @Transactional(readOnly = true)
  public Page<RepositoryResponse> list(UUID organizationId, UUID projectId, Pageable pageable) {
    access.requireProjectAccess(organizationId, projectId, ProjectAccessClient.Access.READ);
    return repositories.findByProjectIdOrderByCreatedAtDesc(projectId, pageable)
        .map(entity -> RepositoryResponse.from(
            entity, git.isEmpty(storage.directoryFor(entity.getOrganizationId(), entity.getId()))));
  }

  @Transactional(readOnly = true)
  public RepositoryResponse get(UUID organizationId, UUID projectId, UUID repositoryId) {
    var entity = load(organizationId, projectId, repositoryId, ProjectAccessClient.Access.READ);
    return RepositoryResponse.from(
        entity, git.isEmpty(storage.directoryFor(entity.getOrganizationId(), entity.getId())));
  }

  @Transactional
  public RepositoryResponse update(
      UUID organizationId, UUID projectId, UUID repositoryId, UpdateRepositoryRequest request) {
    var entity = load(organizationId, projectId, repositoryId, ProjectAccessClient.Access.WRITE);
    entity.setDescription(request.description());
    repositories.save(entity);
    return RepositoryResponse.from(
        entity, git.isEmpty(storage.directoryFor(entity.getOrganizationId(), entity.getId())));
  }

  /**
   * Sets how many approvals of the current changes a pull request needs before it can merge.
   *
   * <p>ADMIN, not WRITE: a rule a developer could lower for their own pull request is no rule.
   */
  @Transactional
  public RepositoryResponse updateMergeRules(
      UUID organizationId, UUID projectId, UUID repositoryId,
      com.devforge.ai.gitservice.dto.GitDtos.MergeRulesRequest request) {
    var entity = load(organizationId, projectId, repositoryId, ProjectAccessClient.Access.ADMIN);
    entity.setRequiredApprovals(request.requiredApprovals());
    repositories.save(entity);
    return RepositoryResponse.from(
        entity, git.isEmpty(storage.directoryFor(entity.getOrganizationId(), entity.getId())));
  }

  /**
   * Deletes the metadata row and the objects.
   *
   * <p>The row goes first here, the opposite order from {@link #create}, for the same reason: if the
   * process dies between the two the leftover is an orphaned directory rather than a row pointing at
   * nothing. An orphaned directory wastes space; a row whose files are gone looks to the user like a
   * repository that still exists and is broken.
   */
  @Transactional
  public void delete(UUID organizationId, UUID projectId, UUID repositoryId) {
    var entity = load(organizationId, projectId, repositoryId, ProjectAccessClient.Access.ADMIN);
    var actor = access.requireCurrentUser();
    var directory = storage.directoryFor(entity.getOrganizationId(), entity.getId());

    repositories.delete(entity);

    outbox.record(
        KafkaTopics.REPOSITORIES,
        EventTypes.REPOSITORY_DELETED,
        organizationId,
        actor.id(),
        MDC.get("correlationId"),
        Map.of(
            "repositoryId", entity.getId().toString(),
            "projectId", projectId.toString(),
            "name", entity.getName()));

    try {
      storage.deleteRecursively(directory);
    } catch (IOException ex) {
      // Logged, not thrown. The row is already gone, so failing the request would tell the user the
      // delete failed when the repository has in fact disappeared from their view.
      log.warn("Removed repository {} but could not fully delete {}: {}",
          entity.getId(), directory, ex.getMessage());
    }
  }

  // ------------------------------------------------------------ git reading

  @Transactional(readOnly = true)
  public java.util.List<BranchResponse> branches(
      UUID organizationId, UUID projectId, UUID repositoryId) {
    var entity = load(organizationId, projectId, repositoryId, ProjectAccessClient.Access.READ);
    return wrap(() -> git.branches(directory(entity), entity.getDefaultBranch()));
  }

  @Transactional(readOnly = true)
  public java.util.List<CommitResponse> commits(
      UUID organizationId, UUID projectId, UUID repositoryId, String ref, Pageable pageable) {
    var entity = load(organizationId, projectId, repositoryId, ProjectAccessClient.Access.READ);
    var resolved = refOrDefault(entity, ref);
    return wrap(() -> git.commits(
        directory(entity),
        resolved,
        (int) pageable.getOffset(),
        pageable.getPageSize()));
  }

  @Transactional(readOnly = true)
  public java.util.List<TreeEntryResponse> tree(
      UUID organizationId, UUID projectId, UUID repositoryId, String ref, String path) {
    var entity = load(organizationId, projectId, repositoryId, ProjectAccessClient.Access.READ);
    var resolved = refOrDefault(entity, ref);
    var safePath = GitPaths.requireSafeRepositoryPath(path);
    return wrap(() -> git.tree(directory(entity), resolved, safePath));
  }

  @Transactional(readOnly = true)
  public BlobResponse blob(
      UUID organizationId, UUID projectId, UUID repositoryId, String ref, String path) {
    var entity = load(organizationId, projectId, repositoryId, ProjectAccessClient.Access.READ);
    var resolved = refOrDefault(entity, ref);
    var safePath = GitPaths.requireSafeRepositoryPath(path);
    return wrap(() -> git.blob(directory(entity), resolved, safePath));
  }

  @Transactional(readOnly = true)
  public DiffResponse diff(
      UUID organizationId, UUID projectId, UUID repositoryId, String from, String to) {
    var entity = load(organizationId, projectId, repositoryId, ProjectAccessClient.Access.READ);
    var fromRef = GitPaths.requireValidRef(from);
    var toRef = GitPaths.requireValidRef(to);
    var entries = wrap(() -> git.diff(directory(entity), fromRef, toRef));
    return new DiffResponse(fromRef, toRef, entries);
  }

  // ------------------------------------------------------------ git writing

  /**
   * Commits one file.
   *
   * <p>Exists so a repository is usable from the browser without an external git client. A git
   * client pushes over HTTP instead, through {@code GitHttpConfig}, which applies the same access
   * rules.
   */
  @Transactional
  public CommitResponse commitFile(
      UUID organizationId, UUID projectId, UUID repositoryId, CommitFileRequest request) {

    var entity = load(organizationId, projectId, repositoryId, ProjectAccessClient.Access.WRITE);
    var user = access.requireCurrentUser();

    var path = GitPaths.requireSafeRepositoryPath(request.path());
    if (path.isEmpty()) {
      throw new IllegalArgumentException("A file path is required");
    }
    var branch = request.branch() == null || request.branch().isBlank()
        ? entity.getDefaultBranch()
        : GitPaths.requireValidBranchName(request.branch());

    var content = request.content() == null ? "" : request.content();

    var commitId = wrap(() -> git.commitFile(
        directory(entity),
        branch,
        path,
        content,
        request.message(),
        user.username(),
        // The account's own address, so history attributes the change to a real identity rather
        // than to the service.
        user.email()));

    outbox.record(
        KafkaTopics.REPOSITORIES,
        EventTypes.REPOSITORY_PUSHED,
        organizationId,
        user.id(),
        MDC.get("correlationId"),
        Map.of(
            "repositoryId", entity.getId().toString(),
            "projectId", projectId.toString(),
            "branch", branch,
            "commitId", commitId,
            "path", path));

    var commits = wrap(() -> git.commits(directory(entity), commitId, 0, 1));
    if (commits.isEmpty()) {
      throw new IllegalStateException("Commit " + commitId + " was written but cannot be read back");
    }
    return commits.get(0);
  }

  /** Changes across all files in one commit; bounds the work a single request can cause. */
  static final long MAX_COMMIT_BYTES = 5L * 1024 * 1024;

  /**
   * Commits several file changes at once, from the editor.
   *
   * <p>Same authorization and event as {@link #commitFile}: the caller must be a project member,
   * and the history names them as author. Paths are validated exactly as a single-file commit's
   * are, so a batch is no way around the path rules.
   */
  @Transactional
  public CommitResponse commitChanges(
      UUID organizationId, UUID projectId, UUID repositoryId, CommitChangesRequest request) {

    var entity = load(organizationId, projectId, repositoryId, ProjectAccessClient.Access.WRITE);
    var user = access.requireCurrentUser();
    var branch = request.branch() == null || request.branch().isBlank()
        ? entity.getDefaultBranch()
        : GitPaths.requireValidBranchName(request.branch());

    long bytes = 0;
    var changes = new java.util.ArrayList<GitOperations.FileChange>(request.changes().size());
    for (var change : request.changes()) {
      var path = GitPaths.requireSafeRepositoryPath(change.path());
      if (path.isEmpty()) {
        throw new IllegalArgumentException("A file path is required");
      }
      if (change.delete() == (change.content() != null)) {
        throw new IllegalArgumentException(
            "Give either new content or delete for " + path + ", not both or neither");
      }
      if (change.content() != null) {
        bytes += change.content().length();
      }
      changes.add(new GitOperations.FileChange(path, change.delete() ? null : change.content()));
    }
    if (bytes > MAX_COMMIT_BYTES) {
      throw new IllegalArgumentException("A commit may carry at most 5 MB of changed content");
    }

    var commitId = wrap(() -> git.commitChanges(
        directory(entity), branch, request.baseCommitId(), changes, request.message(),
        user.username(), user.email()));

    var paths = changes.stream().map(GitOperations.FileChange::path).toList();
    outbox.record(
        KafkaTopics.REPOSITORIES,
        EventTypes.REPOSITORY_PUSHED,
        organizationId,
        user.id(),
        MDC.get("correlationId"),
        Map.of(
            "repositoryId", entity.getId().toString(),
            "projectId", projectId.toString(),
            "branch", branch,
            "commitId", commitId,
            // "path" stays for consumers of the single-file event; "paths" is the whole change.
            "path", paths.get(0),
            "paths", paths));

    var commits = wrap(() -> git.commits(directory(entity), commitId, 0, 1));
    if (commits.isEmpty()) {
      throw new IllegalStateException("Commit " + commitId + " was written but cannot be read back");
    }
    return commits.get(0);
  }

  @Transactional
  public BranchResponse createBranch(
      UUID organizationId, UUID projectId, UUID repositoryId, CreateBranchRequest request) {

    var entity = load(organizationId, projectId, repositoryId, ProjectAccessClient.Access.WRITE);
    var name = GitPaths.requireValidBranchName(request.name());
    var from = request.fromRef() == null || request.fromRef().isBlank()
        ? entity.getDefaultBranch()
        : GitPaths.requireValidRef(request.fromRef());

    var commitId = wrap(() -> git.createBranch(directory(entity), name, from));
    return new BranchResponse(name, commitId, false);
  }

  // ---------------------------------------------------------------- helpers

  private RepositoryEntity load(
      UUID organizationId, UUID projectId, UUID repositoryId, ProjectAccessClient.Access level) {
    access.requireProjectAccess(organizationId, projectId, level);
    return repositories.findByIdAndProjectId(repositoryId, projectId)
        // 404 rather than 403 for a repository in another project: a 403 would confirm the id
        // exists, making this an oracle for enumerating repository ids across tenants.
        .orElseThrow(() -> new ResourceNotFoundException("Repository not found"));
  }

  private java.nio.file.Path directory(RepositoryEntity entity) {
    return storage.directoryFor(entity.getOrganizationId(), entity.getId());
  }

  private String refOrDefault(RepositoryEntity entity, String ref) {
    return ref == null || ref.isBlank()
        ? entity.getDefaultBranch()
        : GitPaths.requireValidRef(ref);
  }

  /**
   * Turns an {@link IOException} from JGit into an unchecked failure.
   *
   * <p>Deliberately does not swallow {@link ResourceNotFoundException} or
   * {@link ResourceConflictException}, which the git layer raises meaningfully and which must keep
   * their status codes.
   */
  private <T> T wrap(GitCall<T> call) {
    try {
      return call.get();
    } catch (ResourceNotFoundException | ResourceConflictException | IllegalArgumentException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalStateException("Git operation failed: " + ex.getMessage(), ex);
    }
  }

  @FunctionalInterface
  private interface GitCall<T> {
    T get() throws Exception;
  }
}
