package com.devforge.ai.authservice.repository;

import com.devforge.ai.authservice.entity.AuditLogEntity;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLogEntity, UUID> {
  Page<AuditLogEntity> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
  Page<AuditLogEntity> findByActionOrderByCreatedAtDesc(String action, Pageable pageable);
}
