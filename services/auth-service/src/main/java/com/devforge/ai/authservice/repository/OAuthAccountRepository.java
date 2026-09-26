package com.devforge.ai.authservice.repository;

import com.devforge.ai.authservice.entity.OAuthAccountEntity;
import com.devforge.ai.authservice.model.OAuthProvider;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OAuthAccountRepository extends JpaRepository<OAuthAccountEntity, UUID> {
  Optional<OAuthAccountEntity> findByProviderAndProviderId(OAuthProvider provider, String providerId);
}
