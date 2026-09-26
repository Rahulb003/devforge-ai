package com.devforge.ai.projectservice.repository;

import com.devforge.ai.projectservice.entity.OrganizationEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrganizationRepository extends JpaRepository<OrganizationEntity, UUID> {
  Optional<OrganizationEntity> findBySlugIgnoreCase(String slug);

  boolean existsBySlugIgnoreCase(String slug);
}
