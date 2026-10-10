package com.devforge.ai.projectservice.repository;

import com.devforge.ai.projectservice.entity.ProjectMemberEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProjectMemberRepository extends JpaRepository<ProjectMemberEntity, UUID> {
  Optional<ProjectMemberEntity> findByProjectIdAndUserId(UUID projectId, UUID userId);

  List<ProjectMemberEntity> findByProjectId(UUID projectId);

  List<ProjectMemberEntity> findByUserId(UUID userId);

  boolean existsByProjectIdAndUserId(UUID projectId, UUID userId);

  /** Every project membership a user holds in one organization: removed when they leave it. */
  List<ProjectMemberEntity> findByProjectOrganizationIdAndUserId(UUID organizationId, UUID userId);
}
