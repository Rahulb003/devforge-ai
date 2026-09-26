package com.devforge.ai.projectservice.controller;

import com.devforge.ai.common.web.ApiResponse;
import com.devforge.ai.projectservice.dto.AddMemberRequest;
import com.devforge.ai.projectservice.dto.CreateProjectRequest;
import com.devforge.ai.projectservice.dto.ProjectMemberResponse;
import com.devforge.ai.projectservice.dto.ProjectResponse;
import com.devforge.ai.projectservice.dto.UpdateProjectRequest;
import com.devforge.ai.projectservice.service.AccessControlService;
import com.devforge.ai.projectservice.service.ProjectService;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Projects are addressed under their organization.
 *
 * <p>Nesting the route is deliberate: the tenant is part of every lookup, so a project id
 * belonging to another organization cannot resolve even if a caller guesses it.
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/projects")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ProjectController {

  private final ProjectService projectService;
  private final AccessControlService accessControl;

  @PostMapping
  public ResponseEntity<ApiResponse<ProjectResponse>> create(
      @PathVariable UUID organizationId, @Valid @RequestBody CreateProjectRequest request) {
    var created = projectService.create(organizationId, request, accessControl.requireCurrentUser());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(new ApiResponse<>(true, created, "Project created"));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<Page<ProjectResponse>>> list(
      @PathVariable UUID organizationId, @PageableDefault(size = 20) Pageable pageable) {
    var projects = projectService.list(organizationId, accessControl.requireCurrentUser(), pageable);
    return ResponseEntity.ok(new ApiResponse<>(true, projects, null));
  }

  @GetMapping("/{projectId}")
  public ResponseEntity<ApiResponse<ProjectResponse>> get(
      @PathVariable UUID organizationId, @PathVariable UUID projectId) {
    var project = projectService.get(organizationId, projectId, accessControl.requireCurrentUser());
    return ResponseEntity.ok(new ApiResponse<>(true, project, null));
  }

  @PatchMapping("/{projectId}")
  public ResponseEntity<ApiResponse<ProjectResponse>> update(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @Valid @RequestBody UpdateProjectRequest request) {
    var updated =
        projectService.update(organizationId, projectId, request, accessControl.requireCurrentUser());
    return ResponseEntity.ok(new ApiResponse<>(true, updated, "Project updated"));
  }

  @PostMapping("/{projectId}/archive")
  public ResponseEntity<ApiResponse<ProjectResponse>> archive(
      @PathVariable UUID organizationId, @PathVariable UUID projectId) {
    var archived =
        projectService.archive(organizationId, projectId, accessControl.requireCurrentUser());
    return ResponseEntity.ok(new ApiResponse<>(true, archived, "Project archived"));
  }

  @DeleteMapping("/{projectId}")
  public ResponseEntity<ApiResponse<Void>> delete(
      @PathVariable UUID organizationId, @PathVariable UUID projectId) {
    projectService.delete(organizationId, projectId, accessControl.requireCurrentUser());
    return ResponseEntity.ok(new ApiResponse<>(true, null, "Project deleted"));
  }

  @GetMapping("/{projectId}/members")
  public ResponseEntity<ApiResponse<List<ProjectMemberResponse>>> listMembers(
      @PathVariable UUID organizationId, @PathVariable UUID projectId) {
    var members =
        projectService.listMembers(organizationId, projectId, accessControl.requireCurrentUser());
    return ResponseEntity.ok(new ApiResponse<>(true, members, null));
  }

  @PostMapping("/{projectId}/members")
  public ResponseEntity<ApiResponse<ProjectMemberResponse>> addMember(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @Valid @RequestBody AddMemberRequest request) {
    var member =
        projectService.addMember(organizationId, projectId, request, accessControl.requireCurrentUser());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(new ApiResponse<>(true, member, "Member added"));
  }

  @DeleteMapping("/{projectId}/members/{userId}")
  public ResponseEntity<ApiResponse<Void>> removeMember(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID userId) {
    projectService.removeMember(organizationId, projectId, userId, accessControl.requireCurrentUser());
    return ResponseEntity.ok(new ApiResponse<>(true, null, "Member removed"));
  }
}
