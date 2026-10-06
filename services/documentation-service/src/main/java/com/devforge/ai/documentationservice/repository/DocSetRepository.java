package com.devforge.ai.documentationservice.repository;

import com.devforge.ai.documentationservice.entity.DocSetEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Doc sets, always scoped by project.
 *
 * <p>A top-level interface, not nested: Spring Data does not create repository beans for interfaces
 * nested inside another type, and the failure is an unsatisfied dependency at startup that points
 * nowhere near the cause.
 */
public interface DocSetRepository extends JpaRepository<DocSetEntity, UUID> {

  Page<DocSetEntity> findByRepositoryIdAndProjectIdOrderByCreatedAtDescIdAsc(
      UUID repositoryId, UUID projectId, Pageable pageable);

  Optional<DocSetEntity> findByIdAndProjectId(UUID id, UUID projectId);

  Optional<DocSetEntity> findFirstByRepositoryIdAndProjectIdOrderByCreatedAtDescIdAsc(
      UUID repositoryId, UUID projectId);
}
