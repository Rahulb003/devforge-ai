package com.devforge.ai.projectservice.repository;

import com.devforge.ai.projectservice.entity.ProjectEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProjectRepository extends JpaRepository<ProjectEntity, UUID> {

  Page<ProjectEntity> findByOrganizationId(UUID organizationId, Pageable pageable);

  /**
   * Fetches a project only when it belongs to the given organization.
   *
   * <p>Looking a project up by id alone and checking the tenant afterwards is the classic IDOR
   * shape: it is one forgotten check away from cross-tenant access. Scoping the query means a
   * mismatched tenant simply finds nothing.
   */
  Optional<ProjectEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

  boolean existsByOrganizationIdAndProjectKeyIgnoreCase(UUID organizationId, String projectKey);
}
