package com.devforge.ai.deploymentservice.execution;

import com.devforge.ai.common.web.ApiResponse;
import com.devforge.ai.deploymentservice.execution.ExecutionDtos.ExecutionDetail;
import com.devforge.ai.deploymentservice.execution.ExecutionDtos.ExecutionSummary;
import com.devforge.ai.deploymentservice.execution.ExecutionDtos.RunRequest;
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

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/projects/{projectId}/executions")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ExecutionController {

  private final ExecutionService executions;

  /** Runs to completion and returns the result: runs are bounded to seconds, so no polling. */
  @PostMapping
  public ResponseEntity<ApiResponse<ExecutionDetail>> run(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @Valid @RequestBody RunRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(
        new ApiResponse<>(true, executions.run(organizationId, projectId, request), null));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<List<ExecutionSummary>>> list(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @RequestParam(defaultValue = "50") int limit) {
    return ResponseEntity.ok(
        new ApiResponse<>(true, executions.list(organizationId, projectId, limit), null));
  }

  @GetMapping("/{executionId}")
  public ResponseEntity<ApiResponse<ExecutionDetail>> get(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID executionId) {
    return ResponseEntity.ok(
        new ApiResponse<>(true, executions.get(organizationId, projectId, executionId), null));
  }
}
