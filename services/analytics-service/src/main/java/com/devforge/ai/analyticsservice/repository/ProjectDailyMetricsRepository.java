package com.devforge.ai.analyticsservice.repository;

import com.devforge.ai.analyticsservice.entity.ProjectDailyMetricsEntity;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Daily metric buckets, always addressed by project. */
public interface ProjectDailyMetricsRepository
    extends JpaRepository<ProjectDailyMetricsEntity, UUID> {

  Optional<ProjectDailyMetricsEntity> findByProjectIdAndMetricDate(UUID projectId, LocalDate day);

  List<ProjectDailyMetricsEntity> findByProjectIdAndMetricDateBetweenOrderByMetricDateAsc(
      UUID projectId, LocalDate from, LocalDate to);
}
