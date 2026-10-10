package com.devforge.ai.aiservice.controller;

import com.devforge.ai.aiservice.model.ModelClient;
import com.devforge.ai.aiservice.service.ExplainService;
import com.devforge.ai.aiservice.service.ExplainService.Explanation;
import com.devforge.ai.common.security.AuthenticatedUser;
import com.devforge.ai.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class AiController {

  private final ExplainService explainService;
  private final ModelClient model;

  public record ExplainRequest(String path, String ref) {}

  /** Whether AI assistance is available here, so the UI can say so before anyone asks. */
  @GetMapping("/api/v1/ai/status")
  public ResponseEntity<ApiResponse<Map<String, Object>>> status() {
    return ResponseEntity.ok(new ApiResponse<>(true,
        Map.of("configured", model.isConfigured(), "model", model.isConfigured() ? model.model() : ""),
        null));
  }

  @PostMapping("/api/v1/organizations/{organizationId}/projects/{projectId}/repositories/{repositoryId}/ai/explain")
  public ResponseEntity<ApiResponse<Explanation>> explain(
      @PathVariable UUID organizationId, @PathVariable UUID projectId, @PathVariable UUID repositoryId,
      @RequestBody ExplainRequest request, @AuthenticationPrincipal AuthenticatedUser user,
      HttpServletRequest http) {
    return ResponseEntity.ok(new ApiResponse<>(true, explainService.explain(
        organizationId, projectId, repositoryId, request.path(), request.ref(), user,
        http.getHeader(HttpHeaders.AUTHORIZATION)), null));
  }
}
