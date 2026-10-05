package com.devforge.ai.reviewservice.controller;

import com.devforge.ai.common.web.ApiResponse;
import com.devforge.ai.reviewservice.dto.ReviewDtos.DismissFindingRequest;
import com.devforge.ai.reviewservice.dto.ReviewDtos.FindingResponse;
import com.devforge.ai.reviewservice.dto.ReviewDtos.ReviewResponse;
import com.devforge.ai.reviewservice.dto.ReviewDtos.RunReviewRequest;
import com.devforge.ai.reviewservice.service.ReviewService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reviews of a repository.
 *
 * <p>Nested under the repository, because a review has no meaning without one. Access is decided by
 * project-service, and the content is read from git-service — review-service owns the findings and
 * nothing else.
 */
@RestController
@RequestMapping(
    "/api/v1/organizations/{organizationId}/projects/{projectId}/repositories/{repositoryId}/reviews")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ReviewController {

  private final ReviewService reviewService;

  /**
   * Runs a review and returns the result.
   *
   * <p>201 with the finished review rather than 202 with a job id: analysis is bounded and
   * synchronous, so there is a result to return and nothing to poll.
   */
  @PostMapping
  public ResponseEntity<ApiResponse<ReviewResponse>> run(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID repositoryId,
      @Valid @RequestBody(required = false) RunReviewRequest request) {
    var effective = request == null ? new RunReviewRequest(null, null) : request;
    var review = reviewService.run(organizationId, projectId, repositoryId, effective);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(new ApiResponse<>(true, review, "Review completed"));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<Page<ReviewResponse>>> list(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID repositoryId,
      @PageableDefault(size = 20) Pageable pageable) {
    return ResponseEntity.ok(new ApiResponse<>(
        true, reviewService.list(organizationId, projectId, repositoryId, pageable), null));
  }

  /** The newest review, for a status badge. 404 when the repository has never been reviewed. */
  @GetMapping("/latest")
  public ResponseEntity<ApiResponse<ReviewResponse>> latest(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID repositoryId) {
    return ResponseEntity.ok(new ApiResponse<>(
        true, reviewService.latest(organizationId, projectId, repositoryId), null));
  }

  @GetMapping("/{reviewId}")
  public ResponseEntity<ApiResponse<ReviewResponse>> get(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID repositoryId,
      @PathVariable UUID reviewId) {
    return ResponseEntity.ok(
        new ApiResponse<>(true, reviewService.get(organizationId, projectId, reviewId), null));
  }

  @GetMapping("/{reviewId}/findings")
  public ResponseEntity<ApiResponse<List<FindingResponse>>> findings(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID repositoryId,
      @PathVariable UUID reviewId) {
    return ResponseEntity.ok(
        new ApiResponse<>(true, reviewService.findings(organizationId, projectId, reviewId), null));
  }

  /**
   * Accepts a finding, with a reason.
   *
   * <p>Does not change the review's gate: the gate records what was found at the time, and letting
   * a dismissal rewrite it would turn it into something anyone can clear.
   */
  @PostMapping("/{reviewId}/findings/{findingId}/dismiss")
  public ResponseEntity<ApiResponse<FindingResponse>> dismiss(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID repositoryId,
      @PathVariable UUID reviewId,
      @PathVariable UUID findingId,
      @Valid @RequestBody DismissFindingRequest request) {
    return ResponseEntity.ok(new ApiResponse<>(
        true,
        reviewService.dismiss(organizationId, projectId, reviewId, findingId, request),
        "Finding dismissed"));
  }
}
