package com.devforge.ai.authservice.repository;

import com.devforge.ai.authservice.entity.PasswordResetTokenEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetTokenEntity, UUID> {
  Optional<PasswordResetTokenEntity> findByToken(String token);

  void deleteByUser(com.devforge.ai.authservice.entity.UserEntity user);
}
