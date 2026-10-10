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
  private final com.devforge.ai.analyticsservice.repository.AuditEntryRepository auditEntries;
  private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
  private final com.devforge.ai.analyticsservice.repository.AuditChainHeadRepository auditChainHeads;

  /** Longest window a single request may ask for, so one query cannot pull years of rows. */
  @Value("${devforge.analytics.max-range-days:366}")
  private int maxRangeDays;

  /**
   * The project's audit log, newest first. Project admins only: it shows who changed access
   * and who deleted what, which is not every member's business.
   */
  @Transactional(readOnly = true)
  public org.springframework.data.domain.Page<com.devforge.ai.analyticsservice.dto.AnalyticsDtos.AuditEntry> audit(
      UUID organizationId, UUID projectId, org.springframework.data.domain.Pageable pageable) {
    projectAccess.requireProjectAccess(
        organizationId, projectId, currentBearerToken(), ProjectAccessClient.Access.ADMIN);
    return auditEntries
        .findByOrganizationIdAndProjectIdOrderByOccurredAtDesc(organizationId, projectId, pageable)
        .map(row -> new com.devforge.ai.analyticsservice.dto.AnalyticsDtos.AuditEntry(
            row.getEventId(), row.getEventType(), row.getSource(), row.getActorId(),
            row.getOccurredAt(), parse(row.getDetails())));
  }

  /** Re-hashes the project's audit chain end to end. Project admins only, like the log itself. */
  @Transactional(readOnly = true)
  public com.devforge.ai.analyticsservice.dto.AnalyticsDtos.AuditVerification verifyAudit(
      UUID organizationId, UUID projectId) {
    projectAccess.requireProjectAccess(
        organizationId, projectId, currentBearerToken(), ProjectAccessClient.Access.ADMIN);
    var chainKey = com.devforge.ai.analyticsservice.entity.AuditChain.key(organizationId, projectId);
    var unchained = auditEntries.countByOrganizationIdAndProjectIdAndChainSequenceIsNull(
        organizationId, projectId);

    long checked = 0;
    var expectedPrevious = com.devforge.ai.analyticsservice.entity.AuditChain.GENESIS;
    // Paged by position rather than offset, so a long chain is read in bounded memory.
    while (true) {
      var page = auditEntries.findByChainKeyAndChainSequenceGreaterThanOrderByChainSequenceAsc(
          chainKey, checked, org.springframework.data.domain.PageRequest.of(0, 500));
      for (var row : page) {
        var position = checked + 1;
        String problem = null;
        if (row.getChainSequence() != position) {
          problem = "the entry is missing";
        } else if (!expectedPrevious.equals(row.getPreviousHash())) {
          problem = "it does not follow the entry before it";
        } else if (!com.devforge.ai.analyticsservice.entity.AuditChain
            .hash(row.getPreviousHash(), position, row).equals(row.getEntryHash())) {
          problem = "its content does not match its hash";
        }
        if (problem != null) {
          return new com.devforge.ai.analyticsservice.dto.AnalyticsDtos.AuditVerification(
              false, checked, unchained, position, problem, null);
        }
        expectedPrevious = row.getEntryHash();
        checked = position;
      }
      if (!page.hasNext()) {
        break;
      }
    }

    // Rows deleted from the end leave a chain that is consistent as far as it goes; only the head
    // knows how far it went.
    var head = auditChainHeads.findById(chainKey);
    var headSequence = head.map(h -> h.getLastSequence()).orElse(0L);
    if (headSequence != checked || (head.isPresent() && !head.get().getLastHash().equals(expectedPrevious))) {
      return new com.devforge.ai.analyticsservice.dto.AnalyticsDtos.AuditVerification(
          false, checked, unchained, checked + 1,
          "the chain stops short of the " + headSequence + " entries it recorded", null);
    }
    return new com.devforge.ai.analyticsservice.dto.AnalyticsDtos.AuditVerification(
        true, checked, unchained, null, null, expectedPrevious);
  }

  private com.fasterxml.jackson.databind.JsonNode parse(String details) {
    try {
      return objectMapper.readTree(details);
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      return objectMapper.getNodeFactory().textNode(details);
    }
  }

  @Transactional(readOnly = true)
  public ProjectActivity activity(
      UUID organizationId, UUID projectId, LocalDate from, LocalDate to) {

    projectAccess.requireProjectAccess(organizationId, projectId, currentBearerToken(), ProjectAccessClient.Access.READ);

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
