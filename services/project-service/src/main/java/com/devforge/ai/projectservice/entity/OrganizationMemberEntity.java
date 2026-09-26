package com.devforge.ai.projectservice.entity;

import com.devforge.ai.common.model.BaseEntity;
import com.devforge.ai.projectservice.model.OrganizationRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Links a user to an organization with a role.
 *
 * <p>This table is the authorization source of truth for tenancy. {@code userId} is a plain UUID
 * rather than a foreign key because users live in the auth service's database; services must not
 * reach into each other's schemas.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "organization_members")
public class OrganizationMemberEntity extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "organization_id", nullable = false)
  private OrganizationEntity organization;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false, length = 50)
  private OrganizationRole role;
}
