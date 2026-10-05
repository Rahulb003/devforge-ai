package com.devforge.ai.reviewservice.dto;

import com.devforge.ai.reviewservice.entity.FindingEntity;
import com.devforge.ai.reviewservice.entity.ReviewEntity;
import com.devforge.ai.reviewservice.model.ReviewModel.FindingCategory;
import com.devforge.ai.reviewservice.model.ReviewModel.GateResult;
import com.devforge.ai.reviewservice.model.ReviewModel.ReviewStatus;
import com.devforge.ai.reviewservice.model.ReviewModel.Severity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Request and response shapes for review-service. */
public final class ReviewDtos {

  private ReviewDtos() {}

  /**
   * @param ref what to analyse. Defaults to the repository's default branch.
   * @param baseRef when set, only what changed between {@code baseRef} and {@code ref} is
   *     analysed — which is what reviewing a proposed change means.
   */
  public record RunReviewRequest(
      @Size(max = 255) String ref,
      @Size(max = 255) String baseRef) {}

  /**
   * @param reason required. A dismissal with no reason is indistinguishable from someone clearing
   *     the list to turn the gate green, and this record exists so that stays visible.
   */
  public record DismissFindingRequest(@NotBlank @Size(max = 500) String reason) {}

  public record ReviewResponse(
      UUID id,
      UUID repositoryId,
      String ref,
      String baseRef,
      ReviewStatus status,
      GateResult gate,
      int filesAnalysed,
      int blockerCount,
      int highCount,
      int mediumCount,
      int lowCount,
      String failureReason,
      UUID requestedBy,
      Instant createdAt,
      Instant completedAt) {

    public static ReviewResponse from(ReviewEntity entity) {
      return new ReviewResponse(
          entity.getId(),
          entity.getRepositoryId(),
          entity.getRef(),
          entity.getBaseRef(),
          entity.getStatus(),
          entity.getGate(),
          entity.getFilesAnalysed(),
          entity.getBlockerCount(),
          entity.getHighCount(),
          entity.getMediumCount(),
          entity.getLowCount(),
          entity.getFailureReason(),
          entity.getRequestedBy(),
          entity.getCreatedAt(),
          entity.getCompletedAt());
    }
  }

  public record FindingResponse(
      UUID id,
      String ruleId,
      Severity severity,
      FindingCategory category,
      String filePath,
      Integer lineNumber,
      String message,
      String snippet,
      boolean dismissed,
      Instant dismissedAt,
      UUID dismissedBy,
      String dismissReason) {

    public static FindingResponse from(FindingEntity entity) {
      return new FindingResponse(
          entity.getId(),
          entity.getRuleId(),
          entity.getSeverity(),
          entity.getCategory(),
          entity.getFilePath(),
          entity.getLineNumber(),
          entity.getMessage(),
          entity.getSnippet(),
          entity.isDismissed(),
          entity.getDismissedAt(),
          entity.getDismissedBy(),
          entity.getDismissReason());
    }
  }
}
