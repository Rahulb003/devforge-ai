package com.devforge.ai.gitservice.repository;

import com.devforge.ai.gitservice.entity.WebhookEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookRepository extends JpaRepository<WebhookEntity, UUID> {

  List<WebhookEntity> findByRepositoryIdOrderByCreatedAtAsc(UUID repositoryId);

  /** Scoped by repository, so a webhook id from another repository resolves to nothing. */
  Optional<WebhookEntity> findByIdAndRepositoryId(UUID id, UUID repositoryId);

  long countByRepositoryId(UUID repositoryId);
}
