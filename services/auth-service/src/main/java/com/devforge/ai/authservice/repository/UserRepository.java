package com.devforge.ai.authservice.repository;

import com.devforge.ai.authservice.entity.UserEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<UserEntity, UUID> {
  Optional<UserEntity> findByEmailIgnoreCase(String email);
  Optional<UserEntity> findByUsernameIgnoreCase(String username);
}
