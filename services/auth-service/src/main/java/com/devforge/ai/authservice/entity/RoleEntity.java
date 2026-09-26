package com.devforge.ai.authservice.entity;

import com.devforge.ai.authservice.model.RoleName;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "roles")
public class RoleEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(name = "name", nullable = false, unique = true, length = 50)
  private RoleName name;
}
