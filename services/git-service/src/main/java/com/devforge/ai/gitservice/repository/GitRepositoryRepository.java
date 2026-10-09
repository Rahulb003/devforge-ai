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

  /**
   * The same lookup, holding a row lock until the transaction ends.
   *
   * <p>Serialises the per-repository work that reads then writes - allocating the next pull
   * request number - so two requests cannot both read "4" and both take "5".
   */
  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @org.springframework.data.jpa.repository.Query(
      "select r from RepositoryEntity r where r.id = :id and r.projectId = :projectId")
  Optional<RepositoryEntity> findForUpdate(UUID id, UUID projectId);

  boolean existsByProjectIdAndNameIgnoreCase(UUID projectId, String name);
}
