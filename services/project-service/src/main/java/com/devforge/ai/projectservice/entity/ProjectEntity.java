package com.devforge.ai.projectservice.entity;

import com.devforge.ai.common.model.BaseEntity;
import com.devforge.ai.projectservice.model.ProjectStatus;
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

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "projects")
public class ProjectEntity extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "organization_id", nullable = false)
  private OrganizationEntity organization;

  @Column(name = "name", nullable = false, length = 150)
  private String name;

  /** Short human-readable key, unique within the organization (e.g. "CORE"). */
  @Column(name = "project_key", nullable = false, length = 20)
  private String projectKey;

  @Column(name = "description", length = 2000)
  private String description;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 50)
  private ProjectStatus status;

  @Column(name = "created_by", nullable = false)
  private UUID createdBy;
}
