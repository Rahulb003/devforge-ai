package com.devforge.ai.authservice.controller;

import com.devforge.ai.authservice.dto.ApiResponseDto;
import com.devforge.ai.authservice.service.AuditChainVerifier;
import com.devforge.ai.authservice.service.AuditChainVerifier.Verification;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The account audit log's integrity. Platform administrators only: it spans every account. */
@RestController
@RequiredArgsConstructor
public class AuditController {

  private final AuditChainVerifier verifier;

  @GetMapping("/api/v1/auth/audit/verification")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<ApiResponseDto<Verification>> verify() {
    return ResponseEntity.ok(ApiResponseDto.<Verification>builder()
        .success(true).data(verifier.verify()).build());
  }
}
