package com.devforge.ai.authservice.repository;

import com.devforge.ai.authservice.entity.AuditChainHeadEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditChainHeadRepository extends JpaRepository<AuditChainHeadEntity, String> {

  /** Creates the head unless it exists; concurrent first writers converge on one row. */
  @Modifying(flushAutomatically = true)
  @Query(nativeQuery = true, value = """
      INSERT INTO audit_chain_head (chain_key, last_sequence, last_hash)
      VALUES (:chainKey, 0, :genesis)
      ON CONFLICT DO NOTHING
      """)
  void createIfAbsent(@Param("chainKey") String chainKey, @Param("genesis") String genesis);

  /** The head, locked until the transaction ends, so two writers cannot claim one position. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT h FROM AuditChainHeadEntity h WHERE h.chainKey = :chainKey")
  Optional<AuditChainHeadEntity> lock(@Param("chainKey") String chainKey);
}
