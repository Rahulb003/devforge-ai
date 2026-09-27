package com.devforge.ai.taskservice.controller;

import com.devforge.ai.common.web.ApiResponse;
import com.devforge.ai.taskservice.dto.CreateSprintRequest;
import com.devforge.ai.taskservice.dto.SprintResponse;
import com.devforge.ai.taskservice.service.SprintService;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/projects/{projectId}/sprints")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class SprintController {

  private final SprintService sprintService;

  @PostMapping
  public ResponseEntity<ApiResponse<SprintResponse>> create(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @Valid @RequestBody CreateSprintRequest request) {
    var created = sprintService.create(organizationId, projectId, request);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(new ApiResponse<>(true, created, "Sprint created"));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<List<SprintResponse>>> list(
      @PathVariable UUID organizationId, @PathVariable UUID projectId) {
    return ResponseEntity.ok(new ApiResponse<>(true, sprintService.list(organizationId, projectId), null));
  }

  @PostMapping("/{sprintId}/start")
  public ResponseEntity<ApiResponse<SprintResponse>> start(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID sprintId) {
    return ResponseEntity.ok(new ApiResponse<>(
        true, sprintService.start(organizationId, projectId, sprintId), "Sprint started"));
  }

  @PostMapping("/{sprintId}/complete")
  public ResponseEntity<ApiResponse<SprintResponse>> complete(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID sprintId) {
    return ResponseEntity.ok(new ApiResponse<>(
        true, sprintService.complete(organizationId, projectId, sprintId), "Sprint completed"));
  }
}
