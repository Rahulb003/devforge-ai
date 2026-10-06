package com.devforge.ai.analyticsservice.controller;

import com.devforge.ai.analyticsservice.dto.AnalyticsDtos.ProjectActivity;
import com.devforge.ai.analyticsservice.service.AnalyticsService;
import com.devforge.ai.common.web.ApiResponse;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Activity for one project, derived from domain events.
 *
 * <p>Scoped to a project rather than an organization because project membership is the
 * authorization boundary the platform already enforces, and project-service owns it.
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/projects/{projectId}/analytics")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class AnalyticsController {

  private final AnalyticsService analyticsService;

  /** Defaults to the last 30 days when no range is given. */
  @GetMapping
  public ResponseEntity<ApiResponse<ProjectActivity>> activity(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return ResponseEntity.ok(new ApiResponse<>(
        true, analyticsService.activity(organizationId, projectId, from, to), null));
  }
}
