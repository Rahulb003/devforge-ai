package com.devforge.ai.analyticsservice.repository;

import com.devforge.ai.analyticsservice.entity.AuditEntryEntity;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEntryRepository extends JpaRepository<AuditEntryEntity, UUID> {

  /** Scoped by organization as well as project, so a project id from another tenant finds nothing. */
  Page<AuditEntryEntity> findByOrganizationIdAndProjectIdOrderByOccurredAtDesc(
      UUID organizationId, UUID projectId, Pageable pageable);

  boolean existsByEventId(UUID eventId);

  org.springframework.data.domain.Slice<AuditEntryEntity> findByChainKeyAndChainSequenceGreaterThanOrderByChainSequenceAsc(
      String chainKey, long after, Pageable pageable);

  long countByOrganizationIdAndProjectIdAndChainSequenceIsNull(UUID organizationId, UUID projectId);
}
