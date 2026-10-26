package com.devforge.ai.deploymentservice.pipeline;

import com.devforge.ai.common.web.ApiResponse;
import com.devforge.ai.deploymentservice.pipeline.PipelineDtos.RunDetail;
import com.devforge.ai.deploymentservice.pipeline.PipelineDtos.RunSummary;
import com.devforge.ai.deploymentservice.pipeline.PipelineDtos.TriggerRequest;
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
@RequestMapping(
    "/api/v1/organizations/{organizationId}/projects/{projectId}/repositories/{repositoryId}/pipelines")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class PipelineController {

  private final PipelineService pipelines;

  /** 202: the run is queued; poll it for progress. */
  @PostMapping
  public ResponseEntity<ApiResponse<RunDetail>> trigger(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID repositoryId,
      @Valid @RequestBody(required = false) TriggerRequest request) {
    var ref = request == null ? null : request.ref();
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(new ApiResponse<>(true,
        pipelines.trigger(organizationId, projectId, repositoryId, ref), null));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<List<RunSummary>>> list(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID repositoryId) {
    return ResponseEntity.ok(
        new ApiResponse<>(true, pipelines.list(organizationId, projectId, repositoryId), null));
  }

  @GetMapping("/{runId}")
  public ResponseEntity<ApiResponse<RunDetail>> get(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID repositoryId,
      @PathVariable UUID runId) {
    return ResponseEntity.ok(new ApiResponse<>(true,
        pipelines.get(organizationId, projectId, repositoryId, runId), null));
  }
}
