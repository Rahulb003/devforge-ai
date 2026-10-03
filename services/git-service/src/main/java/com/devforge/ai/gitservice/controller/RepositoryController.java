package com.devforge.ai.gitservice.controller;

import com.devforge.ai.common.web.ApiResponse;
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
import com.devforge.ai.gitservice.service.RepositoryService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Repositories, addressed under their organization and project.
 *
 * <p>Same shape as tasks, and for the same reason: the lookup is scoped by both ids, so a repository
 * id from another project cannot resolve. Whether the caller may touch the project at all is decided
 * by project-service, which owns that rule.
 *
 * <p>Paths and refs arrive as **query parameters, not path segments**. A file path contains slashes,
 * so putting it in the path would need either a wildcard mapping or encoding that Spring normalises
 * before the handler sees it — both of which make traversal checks harder to reason about. As a query
 * parameter the value arrives intact and is validated once.
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/projects/{projectId}/repositories")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class RepositoryController {

  private final RepositoryService repositoryService;

  @PostMapping
  public ResponseEntity<ApiResponse<RepositoryResponse>> create(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @Valid @RequestBody CreateRepositoryRequest request) {
    var created = repositoryService.create(organizationId, projectId, request);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(new ApiResponse<>(true, created, "Repository created"));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<Page<RepositoryResponse>>> list(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PageableDefault(size = 20) Pageable pageable) {
    return ResponseEntity.ok(new ApiResponse<>(
        true, repositoryService.list(organizationId, projectId, pageable), null));
  }

  @GetMapping("/{repositoryId}")
  public ResponseEntity<ApiResponse<RepositoryResponse>> get(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID repositoryId) {
    return ResponseEntity.ok(new ApiResponse<>(
        true, repositoryService.get(organizationId, projectId, repositoryId), null));
  }

  @PatchMapping("/{repositoryId}")
  public ResponseEntity<ApiResponse<RepositoryResponse>> update(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID repositoryId,
      @Valid @RequestBody UpdateRepositoryRequest request) {
    return ResponseEntity.ok(new ApiResponse<>(
        true, repositoryService.update(organizationId, projectId, repositoryId, request), null));
  }

  @DeleteMapping("/{repositoryId}")
  public ResponseEntity<ApiResponse<Void>> delete(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID repositoryId) {
    repositoryService.delete(organizationId, projectId, repositoryId);
    return ResponseEntity.ok(new ApiResponse<>(true, null, "Repository deleted"));
  }

  @GetMapping("/{repositoryId}/branches")
  public ResponseEntity<ApiResponse<List<BranchResponse>>> branches(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID repositoryId) {
    return ResponseEntity.ok(new ApiResponse<>(
        true, repositoryService.branches(organizationId, projectId, repositoryId), null));
  }

  @PostMapping("/{repositoryId}/branches")
  public ResponseEntity<ApiResponse<BranchResponse>> createBranch(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID repositoryId,
      @Valid @RequestBody CreateBranchRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(
        true,
        repositoryService.createBranch(organizationId, projectId, repositoryId, request),
        "Branch created"));
  }

  @GetMapping("/{repositoryId}/commits")
  public ResponseEntity<ApiResponse<List<CommitResponse>>> commits(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID repositoryId,
      @RequestParam(required = false) String ref,
      @PageableDefault(size = 30) Pageable pageable) {
    return ResponseEntity.ok(new ApiResponse<>(
        true,
        repositoryService.commits(organizationId, projectId, repositoryId, ref, pageable),
        null));
  }

  /** One directory level. Not recursive: a browse must not load an entire repository. */
  @GetMapping("/{repositoryId}/tree")
  public ResponseEntity<ApiResponse<List<TreeEntryResponse>>> tree(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID repositoryId,
      @RequestParam(required = false) String ref,
      @RequestParam(required = false) String path) {
    return ResponseEntity.ok(new ApiResponse<>(
        true,
        repositoryService.tree(organizationId, projectId, repositoryId, ref, path),
        null));
  }

  @GetMapping("/{repositoryId}/blob")
  public ResponseEntity<ApiResponse<BlobResponse>> blob(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID repositoryId,
      @RequestParam(required = false) String ref,
      @RequestParam String path) {
    return ResponseEntity.ok(new ApiResponse<>(
        true,
        repositoryService.blob(organizationId, projectId, repositoryId, ref, path),
        null));
  }

  @GetMapping("/{repositoryId}/diff")
  public ResponseEntity<ApiResponse<DiffResponse>> diff(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID repositoryId,
      @RequestParam String from,
      @RequestParam String to) {
    return ResponseEntity.ok(new ApiResponse<>(
        true,
        repositoryService.diff(organizationId, projectId, repositoryId, from, to),
        null));
  }

  /**
   * Commits a single file.
   *
   * <p>Deliberately not a substitute for {@code git push}: pushing is a separate transport this
   * service does not yet speak. This exists so a repository is usable from the browser.
   */
  @PostMapping("/{repositoryId}/files")
  public ResponseEntity<ApiResponse<CommitResponse>> commitFile(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID repositoryId,
      @Valid @RequestBody CommitFileRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(
        true,
        repositoryService.commitFile(organizationId, projectId, repositoryId, request),
        "Committed"));
  }
}
