package com.devforge.ai.gitservice.service;

import com.devforge.ai.common.events.EventTypes;
import com.devforge.ai.common.events.KafkaTopics;
import com.devforge.ai.common.events.outbox.OutboxEventRecorder;
import com.devforge.ai.common.exception.ResourceConflictException;
import com.devforge.ai.common.exception.ResourceNotFoundException;
import com.devforge.ai.common.security.client.ProjectAccessClient;
import com.devforge.ai.gitservice.dto.GitDtos.CreatePullRequestRequest;
import com.devforge.ai.gitservice.dto.GitDtos.DiffResponse;
import com.devforge.ai.gitservice.dto.GitDtos.MergePullRequestRequest;
import com.devforge.ai.gitservice.dto.GitDtos.PullRequestResponse;
import com.devforge.ai.gitservice.entity.PullRequestEntity;
import com.devforge.ai.gitservice.entity.PullRequestEntity.Status;
import com.devforge.ai.gitservice.entity.RepositoryEntity;
import com.devforge.ai.gitservice.git.GitOperations;
import com.devforge.ai.gitservice.git.GitPaths;
import com.devforge.ai.gitservice.git.RepositoryStorage;
import com.devforge.ai.gitservice.repository.GitRepositoryRepository;
import com.devforge.ai.gitservice.repository.PullRequestRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pull requests: propose, inspect, merge, close.
 *
 * <p>Access is the repository's: a project member may open, merge or close any pull request in the
 * project, the same rule as committing. Nothing is cached about what a pull request changes; it is
 * computed from the branches on every read, so it reflects the repository as it is.
 */
@Service
@RequiredArgsConstructor
public class PullRequestService {

  private final GitRepositoryRepository repositories;
  private final PullRequestRepository pullRequests;
  private final GitAccessService access;
  private final RepositoryStorage storage;
  private final GitOperations git;
  private final OutboxEventRecorder outbox;

  @Transactional
  public PullRequestResponse open(
      UUID organizationId, UUID projectId, UUID repositoryId, CreatePullRequestRequest request) {

    access.requireProjectAccess(organizationId, projectId, ProjectAccessClient.Access.WRITE);
    var user = access.requireCurrentUser();
    // Locked: the next number is read then written, and two requests must not both take it.
    var repository = repositories.findForUpdate(repositoryId, projectId)
        .orElseThrow(() -> new ResourceNotFoundException("Repository not found"));

    var source = GitPaths.requireValidBranchName(request.sourceBranch());
    var target = request.targetBranch() == null || request.targetBranch().isBlank()
        ? repository.getDefaultBranch()
        : GitPaths.requireValidBranchName(request.targetBranch());
    if (source.equals(target)) {
      throw new IllegalArgumentException("A pull request needs two different branches");
    }

    // Both branches must exist, and there must be something to merge.
    var preview = wrap(() -> git.previewMerge(directory(repository), target, source));
    if (preview.alreadyMerged()) {
      throw new IllegalArgumentException(target + " already contains every commit on " + source);
    }
    if (pullRequests.existsByRepositoryIdAndSourceBranchAndTargetBranchAndStatus(
        repositoryId, source, target, Status.OPEN)) {
      throw new ResourceConflictException(
          "An open pull request from " + source + " into " + target + " already exists");
    }

    var now = Instant.now();
    var entity = pullRequests.save(PullRequestEntity.builder()
        .id(UUID.randomUUID())
        .repositoryId(repositoryId)
        .number(pullRequests.maxNumber(repositoryId) + 1)
        .title(request.title().trim())
        .description(request.description())
        .sourceBranch(source)
        .targetBranch(target)
        .status(Status.OPEN)
        .authorId(user.id())
        .createdAt(now)
        .build());

    record(EventTypes.PULL_REQUEST_OPENED, organizationId, projectId, repository, entity, user.id());
    return toResponse(entity, preview);
  }

  @Transactional(readOnly = true)
  public List<PullRequestResponse> list(
      UUID organizationId, UUID projectId, UUID repositoryId, String status) {
    var repository = load(organizationId, projectId, repositoryId, ProjectAccessClient.Access.READ);
    var rows = status == null || status.isBlank()
        ? pullRequests.findByRepositoryIdOrderByNumberDesc(repository.getId())
        : pullRequests.findByRepositoryIdAndStatusOrderByNumberDesc(repository.getId(), parse(status));
    return rows.stream().map(row -> toResponse(row, null)).toList();
  }

  @Transactional(readOnly = true)
  public PullRequestResponse get(UUID organizationId, UUID projectId, UUID repositoryId, int number) {
    var repository = load(organizationId, projectId, repositoryId, ProjectAccessClient.Access.READ);
    var entity = find(repository, number);
    if (entity.getStatus() != Status.OPEN) {
      return toResponse(entity, null);
    }
    try {
      return toResponse(entity, wrap(() -> git.previewMerge(
          directory(repository), entity.getTargetBranch(), entity.getSourceBranch())));
    } catch (ResourceNotFoundException ex) {
      // A branch was deleted under an open pull request: still readable, just not mergeable.
      return toResponse(entity, null);
    }
  }

  /**
   * What the pull request changes: from the merge base to the source head while open, and what the
   * merge commit brought in once merged, so the diff stays meaningful after the branch moves on.
   */
  @Transactional(readOnly = true)
  public DiffResponse diff(UUID organizationId, UUID projectId, UUID repositoryId, int number) {
    var repository = load(organizationId, projectId, repositoryId, ProjectAccessClient.Access.READ);
    var entity = find(repository, number);
    var directory = directory(repository);

    if (entity.getStatus() == Status.MERGED) {
      var merge = wrap(() -> git.commits(directory, entity.getMergeCommitId(), 0, 1)).get(0);
      var firstParent = merge.parentIds().get(0);
      return new DiffResponse(firstParent, merge.id(),
          wrap(() -> git.diff(directory, firstParent, merge.id())));
    }
    var preview = wrap(() -> git.previewMerge(
        directory, entity.getTargetBranch(), entity.getSourceBranch()));
    var from = preview.mergeBase() != null ? preview.mergeBase() : preview.targetHead();
    return new DiffResponse(from, preview.sourceHead(),
        wrap(() -> git.diff(directory, from, preview.sourceHead())));
  }

  @Transactional
  public PullRequestResponse merge(
      UUID organizationId, UUID projectId, UUID repositoryId, int number,
      MergePullRequestRequest request) {

    var repository = load(organizationId, projectId, repositoryId, ProjectAccessClient.Access.WRITE);
    var user = access.requireCurrentUser();
    var entity = find(repository, number);
    requireOpen(entity);

    var message = "Merge pull request #" + entity.getNumber() + " from " + entity.getSourceBranch()
        + "\n\n" + entity.getTitle();
    var commitId = wrap(() -> git.merge(
        directory(repository), entity.getTargetBranch(), entity.getSourceBranch(),
        request == null ? null : request.expectedSourceHead(), message, user.username(), user.email()));

    var now = Instant.now();
    entity.setStatus(Status.MERGED);
    entity.setMergedBy(user.id());
    entity.setMergeCommitId(commitId);
    entity.setClosedAt(now);
    entity.setUpdatedAt(now);
    pullRequests.save(entity);

    record(EventTypes.PULL_REQUEST_MERGED, organizationId, projectId, repository, entity, user.id());
    return toResponse(entity, null);
  }

  @Transactional
  public PullRequestResponse close(UUID organizationId, UUID projectId, UUID repositoryId, int number) {
    var repository = load(organizationId, projectId, repositoryId, ProjectAccessClient.Access.WRITE);
    var user = access.requireCurrentUser();
    var entity = find(repository, number);
    requireOpen(entity);

    var now = Instant.now();
    entity.setStatus(Status.CLOSED);
    entity.setClosedBy(user.id());
    entity.setClosedAt(now);
    entity.setUpdatedAt(now);
    pullRequests.save(entity);

    record(EventTypes.PULL_REQUEST_CLOSED, organizationId, projectId, repository, entity, user.id());
    return toResponse(entity, null);
  }

  // ---------------------------------------------------------------- helpers

  private RepositoryEntity load(
      UUID organizationId, UUID projectId, UUID repositoryId, ProjectAccessClient.Access level) {
    access.requireProjectAccess(organizationId, projectId, level);
    // 404, not 403, for another project's repository: see RepositoryService#load.
    return repositories.findByIdAndProjectId(repositoryId, projectId)
        .orElseThrow(() -> new ResourceNotFoundException("Repository not found"));
  }

  private PullRequestEntity find(RepositoryEntity repository, int number) {
    return pullRequests.findByRepositoryIdAndNumber(repository.getId(), number)
        .orElseThrow(() -> new ResourceNotFoundException("Pull request not found"));
  }

  private static void requireOpen(PullRequestEntity entity) {
    if (entity.getStatus() != Status.OPEN) {
      throw new ResourceConflictException(
          "Pull request #" + entity.getNumber() + " is already " + entity.getStatus().name().toLowerCase());
    }
  }

  private static Status parse(String status) {
    try {
      return Status.valueOf(status.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("status must be one of OPEN, MERGED, CLOSED");
    }
  }

  private java.nio.file.Path directory(RepositoryEntity repository) {
    return storage.directoryFor(repository.getOrganizationId(), repository.getId());
  }

  private void record(
      String type, UUID organizationId, UUID projectId, RepositoryEntity repository,
      PullRequestEntity entity, UUID actor) {
    outbox.record(KafkaTopics.REPOSITORIES, type, organizationId, actor, MDC.get("correlationId"),
        Map.of(
            "repositoryId", repository.getId().toString(),
            "projectId", projectId.toString(),
            "pullRequestId", entity.getId().toString(),
            "number", entity.getNumber(),
            "sourceBranch", entity.getSourceBranch(),
            "targetBranch", entity.getTargetBranch()));
  }

  private static PullRequestResponse toResponse(
      PullRequestEntity entity, GitOperations.MergePreview preview) {
    return new PullRequestResponse(
        entity.getId(), entity.getNumber(), entity.getTitle(), entity.getDescription(),
        entity.getSourceBranch(), entity.getTargetBranch(), entity.getStatus().name(),
        entity.getAuthorId(), entity.getCreatedAt(), entity.getMergedBy(), entity.getMergeCommitId(),
        entity.getClosedAt(),
        preview == null ? null : preview.sourceHead(),
        preview == null ? null : preview.targetHead(),
        preview == null ? null : preview.mergeBase(),
        preview == null ? null : preview.alreadyMerged(),
        preview == null ? null : preview.conflicts());
  }

  private static <T> T wrap(GitCall<T> call) {
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
