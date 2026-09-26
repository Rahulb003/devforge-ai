package com.devforge.ai.authservice.repository;

import com.devforge.ai.authservice.entity.RefreshTokenEntity;
import com.devforge.ai.authservice.entity.UserEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, UUID> {
  Optional<RefreshTokenEntity> findByToken(String token);
  List<RefreshTokenEntity> findByUser(UserEntity user);
  void deleteByUser(UserEntity user);
}
