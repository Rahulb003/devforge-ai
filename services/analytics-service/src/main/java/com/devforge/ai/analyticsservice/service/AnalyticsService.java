package com.devforge.ai.analyticsservice.service;

import com.devforge.ai.analyticsservice.dto.AnalyticsDtos.DailyMetrics;
import com.devforge.ai.analyticsservice.dto.AnalyticsDtos.ProjectActivity;
import com.devforge.ai.analyticsservice.repository.ProjectDailyMetricsRepository;
import com.devforge.ai.common.security.client.ProjectAccessClient;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Reads the aggregated metrics, scoped to a project the caller may see. */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

  private final ProjectDailyMetricsRepository metrics;
  private final ProjectAccessClient projectAccess;

  /** Longest window a single request may ask for, so one query cannot pull years of rows. */
  @Value("${devforge.analytics.max-range-days:366}")
  private int maxRangeDays;

  @Transactional(readOnly = true)
  public ProjectActivity activity(
      UUID organizationId, UUID projectId, LocalDate from, LocalDate to) {

    projectAccess.requireProjectAccess(organizationId, projectId, currentBearerToken());

    var end = to == null ? LocalDate.now(ZoneOffset.UTC) : to;
    var start = from == null ? end.minusDays(29) : from;

    if (start.isAfter(end)) {
      throw new IllegalArgumentException("'from' must not be after 'to'");
    }
    if (start.plusDays(maxRangeDays).isBefore(end)) {
      throw new IllegalArgumentException(
          "The range is longer than " + maxRangeDays + " days. Ask for a shorter window.");
    }

    var stored = metrics.findByProjectIdAndMetricDateBetweenOrderByMetricDateAsc(projectId, start, end).stream()
        .collect(Collectors.toMap(m -> m.getMetricDate(), DailyMetrics::from));

    // Every day in the range, including the quiet ones: a gap in a series reads as missing data.
    var days = new ArrayList<DailyMetrics>();
    for (var day = start; !day.isAfter(end); day = day.plusDays(1)) {
      days.add(stored.getOrDefault(day, DailyMetrics.empty(day)));
    }

    return new ProjectActivity(
        start,
        end,
        days.stream().mapToInt(DailyMetrics::tasksCreated).sum(),
        days.stream().mapToInt(DailyMetrics::tasksCompleted).sum(),
        days.stream().mapToInt(DailyMetrics::tasksAssigned).sum(),
        days.stream().mapToInt(DailyMetrics::commits).sum(),
        days,
        // Stated in the response rather than assumed by the reader: these counts come from events,
        // so anything that happened before this service started consuming is simply not here.
        "Counted from domain events as they were published. Activity from before analytics-service "
            + "began consuming is not included, and the standalone profile has no broker at all, so "
            + "these numbers stay at zero there.");
  }

  private String currentBearerToken() {
    var attributes = RequestContextHolder.getRequestAttributes();
    if (attributes instanceof ServletRequestAttributes servletAttributes) {
      HttpServletRequest request = servletAttributes.getRequest();
      var header = request.getHeader(HttpHeaders.AUTHORIZATION);
      if (header != null && !header.isBlank()) {
        return header;
      }
    }
    throw new AccessDeniedException("No bearer token on the current request");
  }
}
