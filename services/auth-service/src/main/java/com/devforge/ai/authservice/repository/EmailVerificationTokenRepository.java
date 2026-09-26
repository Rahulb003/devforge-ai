package com.devforge.ai.authservice.repository;

import com.devforge.ai.authservice.entity.EmailVerificationTokenEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationTokenEntity, UUID> {
  Optional<EmailVerificationTokenEntity> findByToken(String token);
}
