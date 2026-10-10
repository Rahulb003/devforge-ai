package com.devforge.ai.projectservice.repository;

import com.devforge.ai.projectservice.entity.OrganizationInvitationEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationInvitationRepository
    extends JpaRepository<OrganizationInvitationEntity, UUID> {

  List<OrganizationInvitationEntity> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

  List<OrganizationInvitationEntity> findByEmailOrderByCreatedAtDesc(String email);

  /** Scoped by organization, so an id from another tenant resolves to nothing. */
  Optional<OrganizationInvitationEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
