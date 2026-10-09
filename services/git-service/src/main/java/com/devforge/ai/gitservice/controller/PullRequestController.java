package com.devforge.ai.gitservice.controller;

import com.devforge.ai.common.web.ApiResponse;
import com.devforge.ai.gitservice.dto.GitDtos.CreatePullRequestRequest;
import com.devforge.ai.gitservice.dto.GitDtos.DiffResponse;
import com.devforge.ai.gitservice.dto.GitDtos.MergePullRequestRequest;
import com.devforge.ai.gitservice.dto.GitDtos.PullRequestResponse;
import com.devforge.ai.gitservice.service.PullRequestService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Pull requests within a repository, addressed by their per-repository number. */
@RestController
@RequestMapping(
    "/api/v1/organizations/{organizationId}/projects/{projectId}/repositories/{repositoryId}/pull-requests")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class PullRequestController {

  private final PullRequestService pullRequests;

  @PostMapping
  public ResponseEntity<ApiResponse<PullRequestResponse>> open(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID repositoryId,
      @Valid @RequestBody CreatePullRequestRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(true,
        pullRequests.open(organizationId, projectId, repositoryId, request), "Pull request opened"));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<List<PullRequestResponse>>> list(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID repositoryId,
      @RequestParam(required = false) String status) {
    return ResponseEntity.ok(new ApiResponse<>(true,
        pullRequests.list(organizationId, projectId, repositoryId, status), null));
  }

  @GetMapping("/{number}")
  public ResponseEntity<ApiResponse<PullRequestResponse>> get(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID repositoryId,
      @PathVariable int number) {
    return ResponseEntity.ok(new ApiResponse<>(true,
        pullRequests.get(organizationId, projectId, repositoryId, number), null));
  }

  @GetMapping("/{number}/diff")
  public ResponseEntity<ApiResponse<DiffResponse>> diff(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID repositoryId,
      @PathVariable int number) {
    return ResponseEntity.ok(new ApiResponse<>(true,
        pullRequests.diff(organizationId, projectId, repositoryId, number), null));
  }

  @PostMapping("/{number}/merge")
  public ResponseEntity<ApiResponse<PullRequestResponse>> merge(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID repositoryId,
      @PathVariable int number,
      @Valid @RequestBody(required = false) MergePullRequestRequest request) {
    return ResponseEntity.ok(new ApiResponse<>(true,
        pullRequests.merge(organizationId, projectId, repositoryId, number, request), "Merged"));
  }

  @PostMapping("/{number}/approve")
  public ResponseEntity<ApiResponse<PullRequestResponse>> approve(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID repositoryId,
      @PathVariable int number) {
    return ResponseEntity.ok(new ApiResponse<>(true,
        pullRequests.approve(organizationId, projectId, repositoryId, number), "Approved"));
  }

  @org.springframework.web.bind.annotation.DeleteMapping("/{number}/approve")
  public ResponseEntity<ApiResponse<PullRequestResponse>> withdrawApproval(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID repositoryId,
      @PathVariable int number) {
    return ResponseEntity.ok(new ApiResponse<>(true,
        pullRequests.withdrawApproval(organizationId, projectId, repositoryId, number), "Approval withdrawn"));
  }

  @GetMapping("/{number}/comments")
  public ResponseEntity<ApiResponse<List<com.devforge.ai.gitservice.dto.GitDtos.CommentResponse>>> comments(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID repositoryId,
      @PathVariable int number) {
    return ResponseEntity.ok(new ApiResponse<>(true,
        pullRequests.comments(organizationId, projectId, repositoryId, number), null));
  }

  @PostMapping("/{number}/comments")
  public ResponseEntity<ApiResponse<com.devforge.ai.gitservice.dto.GitDtos.CommentResponse>> addComment(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID repositoryId,
      @PathVariable int number,
      @Valid @RequestBody com.devforge.ai.gitservice.dto.GitDtos.AddCommentRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(true,
        pullRequests.addComment(organizationId, projectId, repositoryId, number, request), "Comment added"));
  }

  @org.springframework.web.bind.annotation.DeleteMapping("/{number}/comments/{commentId}")
  public ResponseEntity<ApiResponse<Void>> deleteComment(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID repositoryId,
      @PathVariable int number,
      @PathVariable UUID commentId) {
    pullRequests.deleteComment(organizationId, projectId, repositoryId, number, commentId);
    return ResponseEntity.ok(new ApiResponse<>(true, null, "Comment deleted"));
  }

  @PostMapping("/{number}/close")
  public ResponseEntity<ApiResponse<PullRequestResponse>> close(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID repositoryId,
      @PathVariable int number) {
    return ResponseEntity.ok(new ApiResponse<>(true,
        pullRequests.close(organizationId, projectId, repositoryId, number), "Closed"));
  }
}
