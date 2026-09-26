package com.devforge.ai.authservice.repository;

import com.devforge.ai.authservice.entity.RoleEntity;
import com.devforge.ai.authservice.model.RoleName;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RoleRepository extends JpaRepository<RoleEntity, UUID> {
  Optional<RoleEntity> findByName(RoleName name);
}
