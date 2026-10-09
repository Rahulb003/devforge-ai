package com.devforge.ai.gitservice.service;

import com.devforge.ai.common.events.EventTypes;
import com.devforge.ai.common.events.KafkaTopics;
import com.devforge.ai.common.events.outbox.OutboxEventRecorder;
import com.devforge.ai.common.exception.ResourceConflictException;
import com.devforge.ai.common.exception.ResourceNotFoundException;
import com.devforge.ai.common.security.client.ProjectAccessClient;
import com.devforge.ai.gitservice.dto.GitDtos.AddCommentRequest;
import com.devforge.ai.gitservice.dto.GitDtos.ApprovalResponse;
import com.devforge.ai.gitservice.dto.GitDtos.CommentResponse;
import com.devforge.ai.gitservice.dto.GitDtos.CreatePullRequestRequest;
import com.devforge.ai.gitservice.dto.GitDtos.DiffResponse;
import com.devforge.ai.gitservice.dto.GitDtos.MergePullRequestRequest;
import com.devforge.ai.gitservice.dto.GitDtos.PullRequestResponse;
import com.devforge.ai.gitservice.entity.PullRequestApprovalEntity;
import com.devforge.ai.gitservice.entity.PullRequestCommentEntity;
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
  private final com.devforge.ai.gitservice.repository.PullRequestApprovalRepository approvals;
  private final com.devforge.ai.gitservice.repository.PullRequestCommentRepository comments;

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
    GitOperations.MergePreview preview = null;
    if (entity.getStatus() == Status.OPEN) {
      try {
        preview = wrap(() -> git.previewMerge(
            directory(repository), entity.getTargetBranch(), entity.getSourceBranch()));
      } catch (ResourceNotFoundException ex) {
        // A branch was deleted under an open pull request: still readable, just not mergeable.
      }
    }
    return withReview(toResponse(entity, preview), entity, repository,
        preview == null ? null : preview.sourceHead());
  }

  /** Adds the approvals, marking which are of the commit now at the source head. */
  private PullRequestResponse withReview(
      PullRequestResponse response, PullRequestEntity entity, RepositoryEntity repository,
      String sourceHead) {
    var approvalRows = approvals.findByPullRequestIdOrderByCreatedAtAsc(entity.getId());
    var list = approvalRows.stream()
        .map(row -> new ApprovalResponse(row.getUserId(), row.getUserName(), row.getCommitId(),
            row.getCommitId().equals(sourceHead), row.getCreatedAt()))
        .toList();
    var current = (int) list.stream().filter(ApprovalResponse::current).count();
    return new PullRequestResponse(
        response.id(), response.number(), response.title(), response.description(),
        response.sourceBranch(), response.targetBranch(), response.status(), response.authorId(),
        response.createdAt(), response.mergedBy(), response.mergeCommitId(), response.closedAt(),
        response.sourceHead(), response.targetHead(), response.mergeBase(), response.alreadyMerged(),
        response.conflicts(), current, repository.getRequiredApprovals(), list);
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

    // The head being merged decides which approvals count, and it is then pinned for the merge
    // itself: if the branch moves between this read and the merge, git.merge refuses.
    var sourceHead = wrap(() -> git.previewMerge(
        directory(repository), entity.getTargetBranch(), entity.getSourceBranch())).sourceHead();
    var expected = request == null ? null : request.expectedSourceHead();
    if (expected != null && !expected.equals(sourceHead)) {
      throw new ResourceConflictException(
          "The source branch has new commits since this pull request was reviewed. Review them first.");
    }
    var required = repository.getRequiredApprovals();
    var current = approvals.findByPullRequestIdOrderByCreatedAtAsc(entity.getId()).stream()
        .filter(row -> row.getCommitId().equals(sourceHead))
        .count();
    if (current < required) {
      throw new ResourceConflictException("This repository needs " + required + " approval"
          + (required == 1 ? "" : "s") + " of the current changes before merging; it has " + current);
    }

    var message = "Merge pull request #" + entity.getNumber() + " from " + entity.getSourceBranch()
        + "\n\n" + entity.getTitle();
    var commitId = wrap(() -> git.merge(
        directory(repository), entity.getTargetBranch(), entity.getSourceBranch(),
        sourceHead, message, user.username(), user.email()));

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

  // ---------------------------------------------------------------- review

  /**
   * Approves the pull request as it is now: the approval is pinned to the current source head.
   *
   * <p>An author cannot approve their own change - the point of approval is a second pair of eyes.
   * Approving again after new commits moves the pin to the new head.
   */
  @Transactional
  public PullRequestResponse approve(UUID organizationId, UUID projectId, UUID repositoryId, int number) {
    var repository = load(organizationId, projectId, repositoryId, ProjectAccessClient.Access.WRITE);
    var user = access.requireCurrentUser();
    var entity = find(repository, number);
    requireOpen(entity);
    if (entity.getAuthorId().equals(user.id())) {
      throw new IllegalArgumentException("You cannot approve your own pull request");
    }
    var head = wrap(() -> git.previewMerge(
        directory(repository), entity.getTargetBranch(), entity.getSourceBranch())).sourceHead();

    var approval = approvals.findByPullRequestIdAndUserId(entity.getId(), user.id())
        .orElseGet(() -> PullRequestApprovalEntity.builder()
            .id(UUID.randomUUID())
            .pullRequestId(entity.getId())
            .userId(user.id())
            .build());
    approval.setUserName(user.username());
    approval.setCommitId(head);
    approval.setCreatedAt(Instant.now());
    approvals.save(approval);
    return get(organizationId, projectId, repositoryId, number);
  }

  @Transactional
  public PullRequestResponse withdrawApproval(
      UUID organizationId, UUID projectId, UUID repositoryId, int number) {
    var repository = load(organizationId, projectId, repositoryId, ProjectAccessClient.Access.WRITE);
    var user = access.requireCurrentUser();
    var entity = find(repository, number);
    approvals.findByPullRequestIdAndUserId(entity.getId(), user.id()).ifPresent(approvals::delete);
    return get(organizationId, projectId, repositoryId, number);
  }

  @Transactional(readOnly = true)
  public List<CommentResponse> comments(
      UUID organizationId, UUID projectId, UUID repositoryId, int number) {
    var repository = load(organizationId, projectId, repositoryId, ProjectAccessClient.Access.READ);
    var entity = find(repository, number);
    return comments.findByPullRequestIdOrderByCreatedAtAsc(entity.getId()).stream()
        .map(PullRequestService::toComment)
        .toList();
  }

  @Transactional
  public CommentResponse addComment(
      UUID organizationId, UUID projectId, UUID repositoryId, int number, AddCommentRequest request) {
    var repository = load(organizationId, projectId, repositoryId, ProjectAccessClient.Access.WRITE);
    var user = access.requireCurrentUser();
    var entity = find(repository, number);
    return toComment(comments.save(PullRequestCommentEntity.builder()
        .id(UUID.randomUUID())
        .pullRequestId(entity.getId())
        .authorId(user.id())
        .authorName(user.username())
        .body(request.body().trim())
        .createdAt(Instant.now())
        .build()));
  }

  /** Only the author deletes a comment. Someone else's is reported as not found, not forbidden. */
  @Transactional
  public void deleteComment(
      UUID organizationId, UUID projectId, UUID repositoryId, int number, UUID commentId) {
    var repository = load(organizationId, projectId, repositoryId, ProjectAccessClient.Access.WRITE);
    var user = access.requireCurrentUser();
    var entity = find(repository, number);
    var comment = comments.findByIdAndPullRequestId(commentId, entity.getId())
        .filter(row -> row.getAuthorId().equals(user.id()))
        .orElseThrow(() -> new ResourceNotFoundException("Comment not found"));
    comments.delete(comment);
  }

  private static CommentResponse toComment(PullRequestCommentEntity row) {
    return new CommentResponse(
        row.getId(), row.getAuthorId(), row.getAuthorName(), row.getBody(), row.getCreatedAt());
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
        preview == null ? null : preview.conflicts(),
        null, null, null);
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
