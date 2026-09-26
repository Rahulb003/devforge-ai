package com.devforge.ai.projectservice.controller;

import com.devforge.ai.common.web.ApiResponse;
import com.devforge.ai.projectservice.dto.CreateOrganizationRequest;
import com.devforge.ai.projectservice.dto.OrganizationResponse;
import com.devforge.ai.projectservice.service.AccessControlService;
import com.devforge.ai.projectservice.service.OrganizationService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Organization endpoints.
 *
 * <p>The class-level PreAuthorize is the outer gate: it only asserts that someone is
 * authenticated. The decision that actually matters — which tenant this caller may see — is made
 * in the service layer against membership rows, because it depends on the resource being
 * addressed and cannot be expressed as a URL-level rule.
 */
@RestController
@RequestMapping("/api/v1/organizations")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class OrganizationController {

  private final OrganizationService organizationService;
  private final AccessControlService accessControl;

  @PostMapping
  public ResponseEntity<ApiResponse<OrganizationResponse>> create(
      @Valid @RequestBody CreateOrganizationRequest request) {
    var created = organizationService.create(request, accessControl.requireCurrentUser());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(new ApiResponse<>(true, created, "Organization created"));
  }

  /** Only organizations the caller belongs to. */
  @GetMapping
  public ResponseEntity<ApiResponse<List<OrganizationResponse>>> list() {
    var organizations = organizationService.listForCurrentUser(accessControl.requireCurrentUser());
    return ResponseEntity.ok(new ApiResponse<>(true, organizations, null));
  }

  @GetMapping("/{organizationId}")
  public ResponseEntity<ApiResponse<OrganizationResponse>> get(@PathVariable UUID organizationId) {
    var organization = organizationService.get(organizationId, accessControl.requireCurrentUser());
    return ResponseEntity.ok(new ApiResponse<>(true, organization, null));
  }

  @DeleteMapping("/{organizationId}")
  public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID organizationId) {
    organizationService.delete(organizationId, accessControl.requireCurrentUser());
    return ResponseEntity.ok(new ApiResponse<>(true, null, "Organization deleted"));
  }
}
