package com.devforge.ai.gitservice.repository;

import com.devforge.ai.gitservice.entity.RepositoryEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository metadata, always scoped by project.
 *
 * <p>There is deliberately no bare {@code findById} in use: a lookup by id alone would resolve a
 * repository belonging to another project or tenant, leaving every caller responsible for
 * remembering the ownership check. Scoping it in the query makes the unsafe version awkward to write
 * by accident, and an id from elsewhere simply resolves to nothing — which the API reports as 404.
 */
public interface GitRepositoryRepository extends JpaRepository<RepositoryEntity, UUID> {

  Page<RepositoryEntity> findByProjectIdOrderByCreatedAtDesc(UUID projectId, Pageable pageable);

  Optional<RepositoryEntity> findByIdAndProjectId(UUID id, UUID projectId);

  boolean existsByProjectIdAndNameIgnoreCase(UUID projectId, String name);
}
