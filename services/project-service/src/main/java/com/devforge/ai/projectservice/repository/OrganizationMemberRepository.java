package com.devforge.ai.projectservice.repository;

import com.devforge.ai.projectservice.entity.OrganizationMemberEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrganizationMemberRepository extends JpaRepository<OrganizationMemberEntity, UUID> {

  /** The caller's membership, and therefore their rights, in one organization. */
  Optional<OrganizationMemberEntity> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);

  /**
   * Every organization the user belongs to.
   *
   * <p>Listing endpoints must start from this rather than from all organizations, or the
   * response leaks the existence of other tenants.
   */
  List<OrganizationMemberEntity> findByUserId(UUID userId);

  List<OrganizationMemberEntity> findByOrganizationId(UUID organizationId);

  boolean existsByOrganizationIdAndUserId(UUID organizationId, UUID userId);

  long countByOrganizationIdAndRole(UUID organizationId, com.devforge.ai.projectservice.model.OrganizationRole role);
}
