package com.devforge.ai.documentationservice.controller;

import com.devforge.ai.common.web.ApiResponse;
import com.devforge.ai.documentationservice.dto.DocDtos.DocSetResponse;
import com.devforge.ai.documentationservice.dto.DocDtos.DocumentResponse;
import com.devforge.ai.documentationservice.dto.DocDtos.GenerateRequest;
import com.devforge.ai.documentationservice.generate.DocumentKind;
import com.devforge.ai.documentationservice.service.DocumentationService;
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
 * Generated documentation for a repository.
 *
 * <p>Nested under the repository, because documentation has no meaning without one. Access is
 * decided by project-service and the content is read from git-service — this service owns the
 * generated documents and nothing else.
 */
@RestController
@RequestMapping(
    "/api/v1/organizations/{organizationId}/projects/{projectId}/repositories/{repositoryId}/docs")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class DocumentationController {

  private final DocumentationService documentationService;

  /** Generates a set and returns it. Synchronous and bounded, so there is nothing to poll. */
  @PostMapping
  public ResponseEntity<ApiResponse<DocSetResponse>> generate(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID repositoryId,
      @Valid @RequestBody(required = false) GenerateRequest request) {
    var effective = request == null ? new GenerateRequest(null) : request;
    var docSet = documentationService.generate(organizationId, projectId, repositoryId, effective);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(new ApiResponse<>(true, docSet, "Documentation generated"));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<Page<DocSetResponse>>> list(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID repositoryId,
      @PageableDefault(size = 20) Pageable pageable) {
    return ResponseEntity.ok(new ApiResponse<>(
        true, documentationService.list(organizationId, projectId, repositoryId, pageable), null));
  }

  /** The newest set. 404 when nothing has been generated yet. */
  @GetMapping("/latest")
  public ResponseEntity<ApiResponse<DocSetResponse>> latest(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID repositoryId) {
    return ResponseEntity.ok(new ApiResponse<>(
        true, documentationService.latest(organizationId, projectId, repositoryId), null));
  }

  /** Which documents a set contains. Without their content — see the single-document endpoint. */
  @GetMapping("/{docSetId}/documents")
  public ResponseEntity<ApiResponse<List<DocumentResponse>>> documents(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID repositoryId,
      @PathVariable UUID docSetId) {
    return ResponseEntity.ok(new ApiResponse<>(
        true, documentationService.documents(organizationId, projectId, docSetId), null));
  }

  /**
   * One document's Markdown.
   *
   * <p>Addressed by kind rather than by document id: a client links to "the API surface of this
   * set", and an id would make that link depend on which generation run produced it.
   */
  @GetMapping("/{docSetId}/documents/{kind}")
  public ResponseEntity<ApiResponse<DocumentResponse>> document(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID repositoryId,
      @PathVariable UUID docSetId,
      @PathVariable DocumentKind kind) {
    return ResponseEntity.ok(new ApiResponse<>(
        true, documentationService.document(organizationId, projectId, docSetId, kind), null));
  }
}
