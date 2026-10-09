package com.devforge.ai.gitservice.dto;

import com.devforge.ai.gitservice.entity.RepositoryEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Request and response shapes for git-service, grouped because each is a few lines and they are
 * only meaningful together.
 */
public final class GitDtos {

  private GitDtos() {}

  // ------------------------------------------------------------------ requests

  /**
   * @param initialBranch the default branch name. Defaults to {@code main} rather than
   *     {@code master}; the value is stored per repository so it is not a global assumption.
   */
  public record CreateRepositoryRequest(
      @NotBlank @Size(max = 100) String name,
      @Size(max = 1000) String description,
      @Size(max = 255) String initialBranch) {}

  public record UpdateRepositoryRequest(@Size(max = 1000) String description) {}

  /**
   * A single-file commit.
   *
   * @param content the file's new content as text. Binary uploads are not supported by this
   *     endpoint; they need a different transport and are not pretended to work here.
   * @param message the commit message. Required — a commit with no message is a commit nobody can
   *     interpret later.
   */
  public record CommitFileRequest(
      @NotBlank @Size(max = 1024) String path,
      @Size(max = 1_000_000) String content,
      @NotBlank @Size(max = 2000) String message,
      @Size(max = 255) String branch) {}

  /**
   * Several file changes committed together, from the editor.
   *
   * <p>{@code baseCommitId} is the commit the editor loaded; if the branch has moved since, the
   * commit is refused with 409 rather than reverting whatever arrived in between.
   */
  public record CommitChangesRequest(
      @NotBlank @Size(max = 2000) String message,
      @Size(max = 255) String branch,
      @Pattern(regexp = "^[0-9a-f]{40}$",
          message = "must be a full 40-character commit id") String baseCommitId,
      @NotEmpty @Size(max = 100) List<@Valid FileChangeRequest> changes) {}

  /** One file: its new content, or {@code delete} to remove it. Exactly one of the two. */
  public record FileChangeRequest(
      @NotBlank @Size(max = 1024) String path,
      @Size(max = 1_000_000) String content,
      boolean delete) {}

  public record CreateBranchRequest(
      @NotBlank @Size(max = 255) String name, @Size(max = 255) String fromRef) {}

  // ----------------------------------------------------------------- responses

  public record RepositoryResponse(
      UUID id,
      UUID projectId,
      UUID organizationId,
      String name,
      String description,
      String defaultBranch,
      /** True until the first commit. A browse of an empty repository is not an error. */
      boolean empty,
      UUID createdBy,
      Instant createdAt,
      Instant updatedAt) {

    public static RepositoryResponse from(RepositoryEntity entity, boolean empty) {
      return new RepositoryResponse(
          entity.getId(),
          entity.getProjectId(),
          entity.getOrganizationId(),
          entity.getName(),
          entity.getDescription(),
          entity.getDefaultBranch(),
          empty,
          entity.getCreatedBy(),
          entity.getCreatedAt(),
          entity.getUpdatedAt());
    }
  }

  public record BranchResponse(String name, String commitId, boolean isDefault) {}

  public record CommitResponse(
      String id,
      String shortId,
      String message,
      String authorName,
      String authorEmail,
      Instant committedAt,
      List<String> parentIds) {}

  /**
   * One entry in a directory listing.
   *
   * @param type {@code FILE} or {@code DIRECTORY}.
   * @param size bytes, for a file. Null for a directory, because computing it means walking the
   *     whole subtree and a listing should not pay for that.
   */
  public record TreeEntryResponse(String name, String path, String type, Long size) {}

  /**
   * A file's contents.
   *
   * @param binary true when the blob is not decodable text. {@code content} is then null rather
   *     than mangled: returning replacement characters would look like corruption of the file
   *     itself.
   * @param truncated true when the file exceeded the configured read limit.
   */
  public record BlobResponse(
      String path, long size, boolean binary, boolean truncated, String content) {}

  public record DiffEntryResponse(
      String changeType, String oldPath, String newPath, int linesAdded, int linesDeleted) {}

  public record DiffResponse(String from, String to, List<DiffEntryResponse> entries) {}

  // ------------------------------------------------------------ pull requests

  public record CreatePullRequestRequest(
      @NotBlank @Size(max = 200) String title,
      @Size(max = 10000) String description,
      @NotBlank @Size(max = 255) String sourceBranch,
      @Size(max = 255) String targetBranch) {}

  /** {@code expectedSourceHead}: the source commit the reviewer saw; see GitOperations#merge. */
  public record MergePullRequestRequest(
      @Pattern(regexp = "^[0-9a-f]{40}$", message = "must be a full 40-character commit id")
          String expectedSourceHead) {}

  /**
   * A pull request. The merge fields are computed from the branches when one pull request is read,
   * and null in a list, where computing them for every row would mean a merge attempt per row.
   */
  public record PullRequestResponse(
      UUID id,
      int number,
      String title,
      String description,
      String sourceBranch,
      String targetBranch,
      String status,
      UUID authorId,
      Instant createdAt,
      UUID mergedBy,
      String mergeCommitId,
      Instant closedAt,
      String sourceHead,
      String targetHead,
      String mergeBase,
      Boolean alreadyMerged,
      List<String> conflicts) {}
}
