package com.devforge.ai.gitservice.webhook;

import com.devforge.ai.common.exception.ResourceNotFoundException;
import com.devforge.ai.common.security.client.ProjectAccessClient;
import com.devforge.ai.gitservice.entity.WebhookEntity;
import com.devforge.ai.gitservice.repository.GitRepositoryRepository;
import com.devforge.ai.gitservice.repository.WebhookRepository;
import com.devforge.ai.gitservice.service.GitAccessService;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A repository's webhooks. Project admins only: a webhook sends the repository's activity away. */
@Service
@RequiredArgsConstructor
public class WebhookService {

  static final int MAX_PER_REPOSITORY = 10;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final WebhookRepository webhooks;
  private final GitRepositoryRepository repositories;
  private final GitAccessService access;
  private final WebhookUrlGuard guard;

  public record WebhookView(UUID id, String url, Instant createdAt, Integer lastStatus,
      String lastError, Instant lastDeliveredAt) {}

  /** The only time the secret is returned. */
  public record CreatedWebhook(WebhookView webhook, String secret) {}

  @Transactional
  public CreatedWebhook create(UUID organizationId, UUID projectId, UUID repositoryId, String url) {
    var repository = load(organizationId, projectId, repositoryId);
    var checked = guard.check(url).toString();
    if (webhooks.countByRepositoryId(repository.getId()) >= MAX_PER_REPOSITORY) {
      throw new IllegalArgumentException("A repository can have at most " + MAX_PER_REPOSITORY + " webhooks");
    }
    var bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    var secret = "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    var saved = webhooks.save(WebhookEntity.builder()
        .repositoryId(repository.getId())
        .url(checked)
        .secret(secret)
        .createdBy(access.requireCurrentUser().id())
        .build());
    return new CreatedWebhook(view(saved), secret);
  }

  @Transactional(readOnly = true)
  public List<WebhookView> list(UUID organizationId, UUID projectId, UUID repositoryId) {
    var repository = load(organizationId, projectId, repositoryId);
    return webhooks.findByRepositoryIdOrderByCreatedAtAsc(repository.getId()).stream().map(WebhookService::view).toList();
  }

  @Transactional
  public void delete(UUID organizationId, UUID projectId, UUID repositoryId, UUID webhookId) {
    var repository = load(organizationId, projectId, repositoryId);
    webhooks.delete(webhooks.findByIdAndRepositoryId(webhookId, repository.getId())
        .orElseThrow(() -> new ResourceNotFoundException("Webhook not found")));
  }

  private com.devforge.ai.gitservice.entity.RepositoryEntity load(UUID organizationId, UUID projectId, UUID repositoryId) {
    access.requireProjectAccess(organizationId, projectId, ProjectAccessClient.Access.ADMIN);
    return repositories.findByIdAndProjectId(repositoryId, projectId)
        .filter(r -> r.getOrganizationId().equals(organizationId))
        .orElseThrow(() -> new ResourceNotFoundException("Repository not found"));
  }

  private static WebhookView view(WebhookEntity w) {
    return new WebhookView(w.getId(), w.getUrl(), w.getCreatedAt(), w.getLastStatus(), w.getLastError(),
        w.getLastDeliveredAt());
  }
}
