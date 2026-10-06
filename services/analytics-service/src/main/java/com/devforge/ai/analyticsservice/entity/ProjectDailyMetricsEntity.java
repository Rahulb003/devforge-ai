package com.devforge.ai.analyticsservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One project's activity on one day. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "project_daily_metrics")
public class ProjectDailyMetricsEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "project_id", nullable = false, updatable = false)
  private UUID projectId;

  @Column(name = "organization_id", nullable = false, updatable = false)
  private UUID organizationId;

  /**
   * The day the activity happened, in UTC.
   *
   * <p>Derived from the event's own timestamp, not from when it was consumed: a replay or a
   * backlogged consumer must still put the activity on the day it occurred, or every outage would
   * leave a visible spike on the wrong date.
   */
  @Column(name = "metric_date", nullable = false, updatable = false)
  private LocalDate metricDate;

  @Column(name = "tasks_created", nullable = false)
  private int tasksCreated;

  @Column(name = "tasks_completed", nullable = false)
  private int tasksCompleted;

  @Column(name = "tasks_assigned", nullable = false)
  private int tasksAssigned;

  @Column(name = "commits", nullable = false)
  private int commits;

  @Column(name = "repositories_created", nullable = false)
  private int repositoriesCreated;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  @PreUpdate
  void touch() {
    if (id == null) {
      id = UUID.randomUUID();
    }
    updatedAt = Instant.now();
  }
}
