package com.devforge.ai.analyticsservice.repository;

import com.devforge.ai.analyticsservice.entity.AuditChainHeadEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditChainHeadRepository extends JpaRepository<AuditChainHeadEntity, String> {

  /**
   * The head, locked until the transaction ends. Two consumer threads appending to one chain
   * would otherwise both read the same head and write two entries claiming the same position.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT h FROM AuditChainHeadEntity h WHERE h.chainKey = :chainKey")
  Optional<AuditChainHeadEntity> lock(@Param("chainKey") String chainKey);
}
