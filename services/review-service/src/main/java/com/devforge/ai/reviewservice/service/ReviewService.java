package com.devforge.ai.reviewservice.service;

import com.devforge.ai.common.git.RepositoryFile;
import com.devforge.ai.common.exception.ResourceConflictException;
import com.devforge.ai.common.exception.ResourceNotFoundException;
import com.devforge.ai.common.security.AuthenticatedUser;
import com.devforge.ai.common.security.client.ProjectAccessClient;
import com.devforge.ai.reviewservice.analysis.AnalysisEngine;
import com.devforge.ai.common.git.GitContentClient;
import com.devforge.ai.reviewservice.dto.ReviewDtos.DismissFindingRequest;
import com.devforge.ai.reviewservice.dto.ReviewDtos.FindingResponse;
import com.devforge.ai.reviewservice.dto.ReviewDtos.ReviewResponse;
import com.devforge.ai.reviewservice.dto.ReviewDtos.RunReviewRequest;
import com.devforge.ai.reviewservice.entity.FindingEntity;
import com.devforge.ai.reviewservice.entity.ReviewEntity;
import com.devforge.ai.reviewservice.model.ReviewModel.ReviewStatus;
import com.devforge.ai.reviewservice.model.ReviewModel.Severity;
import com.devforge.ai.reviewservice.repository.FindingRepository;
import com.devforge.ai.reviewservice.repository.ReviewRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Runs reviews and serves their results.
 *
 * <p>Analysis is synchronous. It is bounded — a fixed number of files, each under a size cap — so a
 * review completes in a predictable time, and a synchronous call means the caller knows the outcome
 * without polling. Moving it to a queue becomes worth it when reviews get slow enough to time out,
 * and that is a change to make with evidence rather than in advance.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

  private final ReviewRepository reviews;
  private final FindingRepository findings;
  private final ProjectAccessClient projectAccess;
  private final GitContentClient gitContent;
  private final AnalysisEngine engine;
  private final ReviewFailureRecorder failureRecorder;

  // ------------------------------------------------------------------ running

  /**
   * Analyses a repository and records the result.
   *
   * <p>The review row is written before analysis starts and updated after, so a run that dies
   * halfway leaves a {@code RUNNING} row rather than no trace at all. Silence would be
   * indistinguishable from "nobody ever asked".
   */
  @Transactional
  public ReviewResponse run(
      UUID organizationId, UUID projectId, UUID repositoryId, RunReviewRequest request) {

    requireProjectAccess(organizationId, projectId, ProjectAccessClient.Access.WRITE);
    var user = requireCurrentUser();
    var token = currentBearerToken();

    var ref = request.ref() == null || request.ref().isBlank() ? null : request.ref().trim();
    var baseRef =
        request.baseRef() == null || request.baseRef().isBlank() ? null : request.baseRef().trim();

    if (baseRef != null && baseRef.equals(ref)) {
      throw new ResourceConflictException(
          "baseRef and ref are the same, so there is nothing to compare");
    }

    // git-service is the authority on refs, so the default is resolved there rather than guessed.
    var resolvedRef = ref == null ? defaultBranch(organizationId, projectId, repositoryId, token) : ref;

    var review = reviews.save(ReviewEntity.builder()
        .projectId(projectId)
        .organizationId(organizationId)
        .repositoryId(repositoryId)
        .ref(resolvedRef)
        .baseRef(baseRef)
        .status(ReviewStatus.RUNNING)
        .requestedBy(user.id())
        .build());

    var context = new GitContentClient.Context(organizationId, projectId, repositoryId);

    List<RepositoryFile> files;
    try {
      files = gitContent.filesToAnalyse(context, resolvedRef, baseRef, token);
    } catch (GitContentClient.GitServiceUnavailableException ex) {
      // Recorded as FAILED, not as a clean review. "No problems found" when the code could not be
      // read is the most dangerous possible result: it looks like a pass.
      //
      // Written through a separate bean in its own transaction. Saving it on this transaction and
      // then rethrowing rolled the row back with everything else, so an outage left no trace -
      // which is indistinguishable from nobody having asked for a review.
      failureRecorder.recordFailure(
          organizationId, projectId, repositoryId, resolvedRef, baseRef, user.id(), ex.getMessage());
      throw ex;
    }

    var result = engine.analyse(files);

    for (var finding : result.findings()) {
      findings.save(FindingEntity.builder()
          .reviewId(review.getId())
          .ruleId(finding.ruleId())
          .severity(finding.severity())
          .category(finding.category())
          .filePath(finding.filePath())
          .lineNumber(finding.lineNumber())
          .message(finding.message())
          .snippet(finding.snippet())
          .build());
    }

    review.setStatus(ReviewStatus.COMPLETED);
    review.setGate(result.gate());
    review.setFilesAnalysed(result.filesAnalysed());
    review.setBlockerCount(result.counts().get(Severity.BLOCKER));
    review.setHighCount(result.counts().get(Severity.HIGH));
    review.setMediumCount(result.counts().get(Severity.MEDIUM));
    review.setLowCount(result.counts().get(Severity.LOW));
    review.setCompletedAt(Instant.now());

    log.info("Review {} of repository {} at {}: {} after {} file(s), {} finding(s)",
        review.getId(), repositoryId, resolvedRef, result.gate(),
        result.filesAnalysed(), result.findings().size());

    return ReviewResponse.from(reviews.save(review));
  }

  // ------------------------------------------------------------------ reading

  @Transactional(readOnly = true)
  public Page<ReviewResponse> list(
      UUID organizationId, UUID projectId, UUID repositoryId, Pageable pageable) {
    requireProjectAccess(organizationId, projectId, ProjectAccessClient.Access.READ);
    return reviews
        .findByRepositoryIdAndProjectIdOrderByCreatedAtDescIdAsc(repositoryId, projectId, pageable)
        .map(ReviewResponse::from);
  }

  @Transactional(readOnly = true)
  public ReviewResponse get(UUID organizationId, UUID projectId, UUID reviewId) {
    return ReviewResponse.from(load(organizationId, projectId, reviewId, ProjectAccessClient.Access.READ));
  }

  /** The newest review for a repository, which is what a status badge shows. */
  @Transactional(readOnly = true)
  public ReviewResponse latest(UUID organizationId, UUID projectId, UUID repositoryId) {
    requireProjectAccess(organizationId, projectId, ProjectAccessClient.Access.READ);
    return reviews
        .findFirstByRepositoryIdAndProjectIdOrderByCreatedAtDescIdAsc(repositoryId, projectId)
        .map(ReviewResponse::from)
        .orElseThrow(() -> new ResourceNotFoundException("This repository has not been reviewed yet"));
  }

  @Transactional(readOnly = true)
  public List<FindingResponse> findings(UUID organizationId, UUID projectId, UUID reviewId) {
    var review = load(organizationId, projectId, reviewId, ProjectAccessClient.Access.READ);
    return findings.findByReviewIdOrderBySeverityAscFilePathAscLineNumberAsc(review.getId())
        .stream()
        .map(FindingResponse::from)
        .toList();
  }

  // ------------------------------------------------------------- dismissing

  /**
   * Marks a finding as accepted.
   *
   * <p>Dismissing does <strong>not</strong> change the review's gate or counts. The gate records
   * what the analysis found at the time; letting a dismissal rewrite it would make the history
   * useless and turn the gate into something anyone can clear. A client that wants "outstanding"
   * numbers can count undismissed findings.
   */
  @Transactional
  public FindingResponse dismiss(
      UUID organizationId, UUID projectId, UUID reviewId, UUID findingId,
      DismissFindingRequest request) {

    var review = load(organizationId, projectId, reviewId, ProjectAccessClient.Access.WRITE);
    var user = requireCurrentUser();

    var finding = findings.findByIdAndReviewId(findingId, review.getId())
        .orElseThrow(() -> new ResourceNotFoundException("Finding not found"));

    if (finding.isDismissed()) {
      throw new ResourceConflictException("This finding has already been dismissed");
    }

    finding.setDismissedAt(Instant.now());
    finding.setDismissedBy(user.id());
    finding.setDismissReason(request.reason().trim());
    return FindingResponse.from(findings.save(finding));
  }

  // ---------------------------------------------------------------- helpers

  private String defaultBranch(
      UUID organizationId, UUID projectId, UUID repositoryId, String token) {
    // Asked for rather than assumed: "main" is only a convention, and the repository records its
    // own default.
    return gitContent.defaultBranch(
        new GitContentClient.Context(organizationId, projectId, repositoryId), token);
  }

  private ReviewEntity load(UUID organizationId, UUID projectId, UUID reviewId, ProjectAccessClient.Access level) {
    requireProjectAccess(organizationId, projectId, level);
    return reviews.findByIdAndProjectId(reviewId, projectId)
        // 404 rather than 403 for a review in another project: a 403 would confirm the id exists.
        .orElseThrow(() -> new ResourceNotFoundException("Review not found"));
  }

  private void requireProjectAccess(UUID organizationId, UUID projectId, ProjectAccessClient.Access level) {
    projectAccess.requireProjectAccess(organizationId, projectId, currentBearerToken(), level);
  }

  private AuthenticatedUser requireCurrentUser() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.isAuthenticated()
        && authentication.getPrincipal() instanceof AuthenticatedUser user) {
      return user;
    }
    throw new AccessDeniedException("Not authenticated");
  }

  private String currentBearerToken() {
    var attributes = RequestContextHolder.getRequestAttributes();
    if (attributes instanceof ServletRequestAttributes servletAttributes) {
      HttpServletRequest request = servletAttributes.getRequest();
      var header = request.getHeader(HttpHeaders.AUTHORIZATION);
      if (header != null && !header.isBlank()) {
        return header;
      }
    }
    throw new AccessDeniedException("No bearer token on the current request");
  }
}
