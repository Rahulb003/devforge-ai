package com.devforge.ai.authservice.repository;

import com.devforge.ai.authservice.entity.LoginHistoryEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LoginHistoryRepository extends JpaRepository<LoginHistoryEntity, UUID> {
  Page<LoginHistoryEntity> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
  long countByUserIdAndStatusAndCreatedAtAfter(UUID userId, String status, Instant after);
}
