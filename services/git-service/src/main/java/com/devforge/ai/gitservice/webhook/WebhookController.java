package com.devforge.ai.gitservice.webhook;

import com.devforge.ai.common.web.ApiResponse;
import com.devforge.ai.gitservice.webhook.WebhookService.CreatedWebhook;
import com.devforge.ai.gitservice.webhook.WebhookService.WebhookView;
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

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/projects/{projectId}/repositories/{repositoryId}/webhooks")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class WebhookController {

  private final WebhookService webhooks;

  public record CreateWebhookRequest(String url) {}

  @GetMapping
  public ResponseEntity<ApiResponse<List<WebhookView>>> list(
      @PathVariable UUID organizationId, @PathVariable UUID projectId, @PathVariable UUID repositoryId) {
    return ResponseEntity.ok(new ApiResponse<>(true, webhooks.list(organizationId, projectId, repositoryId), null));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<CreatedWebhook>> create(
      @PathVariable UUID organizationId, @PathVariable UUID projectId, @PathVariable UUID repositoryId,
      @RequestBody CreateWebhookRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(true,
        webhooks.create(organizationId, projectId, repositoryId, request.url()),
        "Copy the secret now: it cannot be shown again"));
  }

  @DeleteMapping("/{webhookId}")
  public ResponseEntity<ApiResponse<Void>> delete(
      @PathVariable UUID organizationId, @PathVariable UUID projectId, @PathVariable UUID repositoryId,
      @PathVariable UUID webhookId) {
    webhooks.delete(organizationId, projectId, repositoryId, webhookId);
    return ResponseEntity.ok(new ApiResponse<>(true, null, "Webhook deleted"));
  }
}
