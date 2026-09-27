package com.devforge.ai.taskservice.entity;

import com.devforge.ai.common.model.BaseEntity;
import com.devforge.ai.taskservice.model.SprintStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** A time-boxed grouping of tasks within one project. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "sprints")
public class SprintEntity extends BaseEntity {

  @Column(name = "project_id", nullable = false)
  private UUID projectId;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "name", nullable = false, length = 150)
  private String name;

  @Column(name = "goal", length = 1000)
  private String goal;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 50)
  private SprintStatus status;

  @Column(name = "start_date")
  private LocalDate startDate;

  @Column(name = "end_date")
  private LocalDate endDate;

  @Column(name = "created_by", nullable = false)
  private UUID createdBy;
}
