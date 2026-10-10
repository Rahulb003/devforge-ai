package com.devforge.ai.authservice.controller;

import com.devforge.ai.authservice.dto.ApiResponseDto;
import com.devforge.ai.authservice.security.UserPrincipal;
import com.devforge.ai.authservice.service.PersonalAccessTokenService;
import com.devforge.ai.authservice.service.PersonalAccessTokenService.CreatedToken;
import com.devforge.ai.authservice.service.PersonalAccessTokenService.TokenView;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own personal access tokens. */
@RestController
@RequestMapping("/api/v1/auth/tokens")
@RequiredArgsConstructor
public class PersonalAccessTokenController {

  private final PersonalAccessTokenService tokens;

  public record CreateTokenRequest(String name, Integer expiresInDays) {}

  @GetMapping
  public ResponseEntity<ApiResponseDto<List<TokenView>>> list(
      @AuthenticationPrincipal UserPrincipal principal) {
    return ResponseEntity.ok(ApiResponseDto.<List<TokenView>>builder()
        .success(true).data(tokens.list(principal.getId())).build());
  }

  @PostMapping
  public ResponseEntity<ApiResponseDto<CreatedToken>> create(
      @AuthenticationPrincipal UserPrincipal principal, @RequestBody CreateTokenRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponseDto.<CreatedToken>builder()
        .success(true)
        .data(tokens.create(principal.getId(), request.name(), request.expiresInDays()))
        .message("Copy the token now: it cannot be shown again")
        .build());
  }

  @DeleteMapping("/{tokenId}")
  public ResponseEntity<ApiResponseDto<Void>> revoke(
      @AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID tokenId) {
    tokens.revoke(principal.getId(), tokenId);
    return ResponseEntity.ok(ApiResponseDto.<Void>builder()
        .success(true).message("Token revoked").build());
  }
}
