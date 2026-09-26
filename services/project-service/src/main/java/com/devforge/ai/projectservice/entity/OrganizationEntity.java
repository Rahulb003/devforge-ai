package com.devforge.ai.projectservice.entity;

import com.devforge.ai.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** The tenant boundary: every project belongs to exactly one organization. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "organizations")
public class OrganizationEntity extends BaseEntity {

  @Column(name = "name", nullable = false, length = 150)
  private String name;

  @Column(name = "slug", nullable = false, unique = true, length = 100)
  private String slug;

  @Column(name = "description", length = 1000)
  private String description;
}
