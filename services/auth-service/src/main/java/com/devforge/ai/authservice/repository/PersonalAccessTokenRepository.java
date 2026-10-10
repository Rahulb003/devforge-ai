package com.devforge.ai.authservice.repository;

import com.devforge.ai.authservice.entity.PersonalAccessTokenEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonalAccessTokenRepository extends JpaRepository<PersonalAccessTokenEntity, UUID> {

  List<PersonalAccessTokenEntity> findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(UUID userId);

  /** Scoped by owner, so another user's token id resolves to nothing. */
  Optional<PersonalAccessTokenEntity> findByIdAndUserId(UUID id, UUID userId);

  Optional<PersonalAccessTokenEntity> findByTokenHash(String tokenHash);

  long countByUserIdAndRevokedAtIsNull(UUID userId);
}
