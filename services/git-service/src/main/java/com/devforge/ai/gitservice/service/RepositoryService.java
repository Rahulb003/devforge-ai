package com.devforge.ai.gitservice.service;

import com.devforge.ai.common.events.EventTypes;
import com.devforge.ai.common.events.KafkaTopics;
import com.devforge.ai.common.events.outbox.OutboxEventRecorder;
import com.devforge.ai.common.exception.ResourceConflictException;
import com.devforge.ai.common.exception.ResourceNotFoundException;
import com.devforge.ai.gitservice.dto.GitDtos.BlobResponse;
import com.devforge.ai.gitservice.dto.GitDtos.BranchResponse;
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

    access.requireProjectAccess(organizationId, projectId);
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
    access.requireProjectAccess(organizationId, projectId);
    return repositories.findByProjectIdOrderByCreatedAtDesc(projectId, pageable)
        .map(entity -> RepositoryResponse.from(
            entity, git.isEmpty(storage.directoryFor(entity.getOrganizationId(), entity.getId()))));
  }

  @Transactional(readOnly = true)
  public RepositoryResponse get(UUID organizationId, UUID projectId, UUID repositoryId) {
    var entity = load(organizationId, projectId, repositoryId);
    return RepositoryResponse.from(
        entity, git.isEmpty(storage.directoryFor(entity.getOrganizationId(), entity.getId())));
  }

  @Transactional
  public RepositoryResponse update(
      UUID organizationId, UUID projectId, UUID repositoryId, UpdateRepositoryRequest request) {
    var entity = load(organizationId, projectId, repositoryId);
    entity.setDescription(request.description());
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
    var entity = load(organizationId, projectId, repositoryId);
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
    var entity = load(organizationId, projectId, repositoryId);
    return wrap(() -> git.branches(directory(entity), entity.getDefaultBranch()));
  }

  @Transactional(readOnly = true)
  public java.util.List<CommitResponse> commits(
      UUID organizationId, UUID projectId, UUID repositoryId, String ref, Pageable pageable) {
    var entity = load(organizationId, projectId, repositoryId);
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
    var entity = load(organizationId, projectId, repositoryId);
    var resolved = refOrDefault(entity, ref);
    var safePath = GitPaths.requireSafeRepositoryPath(path);
    return wrap(() -> git.tree(directory(entity), resolved, safePath));
  }

  @Transactional(readOnly = true)
  public BlobResponse blob(
      UUID organizationId, UUID projectId, UUID repositoryId, String ref, String path) {
    var entity = load(organizationId, projectId, repositoryId);
    var resolved = refOrDefault(entity, ref);
    var safePath = GitPaths.requireSafeRepositoryPath(path);
    return wrap(() -> git.blob(directory(entity), resolved, safePath));
  }

  @Transactional(readOnly = true)
  public DiffResponse diff(
      UUID organizationId, UUID projectId, UUID repositoryId, String from, String to) {
    var entity = load(organizationId, projectId, repositoryId);
    var fromRef = GitPaths.requireValidRef(from);
    var toRef = GitPaths.requireValidRef(to);
    var entries = wrap(() -> git.diff(directory(entity), fromRef, toRef));
    return new DiffResponse(fromRef, toRef, entries);
  }

  // ------------------------------------------------------------ git writing

  /**
   * Commits one file.
   *
   * <p>Exists so a repository is usable from the browser without an external git client. It is not a
   * substitute for push: pushing over HTTP or SSH is a separate transport that this service does not
   * yet speak, and nothing here pretends otherwise.
   */
  @Transactional
  public CommitResponse commitFile(
      UUID organizationId, UUID projectId, UUID repositoryId, CommitFileRequest request) {

    var entity = load(organizationId, projectId, repositoryId);
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

  @Transactional
  public BranchResponse createBranch(
      UUID organizationId, UUID projectId, UUID repositoryId, CreateBranchRequest request) {

    var entity = load(organizationId, projectId, repositoryId);
    var name = GitPaths.requireValidBranchName(request.name());
    var from = request.fromRef() == null || request.fromRef().isBlank()
        ? entity.getDefaultBranch()
        : GitPaths.requireValidRef(request.fromRef());

    var commitId = wrap(() -> git.createBranch(directory(entity), name, from));
    return new BranchResponse(name, commitId, false);
  }

  // ---------------------------------------------------------------- helpers

  private RepositoryEntity load(UUID organizationId, UUID projectId, UUID repositoryId) {
    access.requireProjectAccess(organizationId, projectId);
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
