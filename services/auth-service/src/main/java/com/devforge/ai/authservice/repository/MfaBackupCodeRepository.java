package com.devforge.ai.authservice.repository;

import com.devforge.ai.authservice.entity.MfaBackupCodeEntity;
import com.devforge.ai.authservice.entity.UserEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MfaBackupCodeRepository extends JpaRepository<MfaBackupCodeEntity, UUID> {

  /** All codes for a user, used and unused. Unused ones are filtered in the service. */
  List<MfaBackupCodeEntity> findByUser(UserEntity user);

  List<MfaBackupCodeEntity> findByUserIdAndUsedAtIsNull(UUID userId);

  long countByUserIdAndUsedAtIsNull(UUID userId);

  void deleteByUser(UserEntity user);
}
