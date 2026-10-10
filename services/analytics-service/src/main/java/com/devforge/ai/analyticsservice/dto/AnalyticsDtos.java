package com.devforge.ai.analyticsservice.dto;

import com.devforge.ai.analyticsservice.entity.ProjectDailyMetricsEntity;
import java.time.LocalDate;
import java.util.List;

/** Response shapes for analytics-service. */
public final class AnalyticsDtos {

  /** One audit-log entry. {@code details} is the event payload as recorded. */
  public record AuditEntry(
      java.util.UUID eventId, String eventType, String source, java.util.UUID actorId,
      java.time.Instant occurredAt, com.fasterxml.jackson.databind.JsonNode details) {}

  /**
   * The outcome of re-hashing a project's audit chain.
   *
   * @param intact every chained entry hashes as recorded, in sequence with no gaps, and the chain
   *     ends where its head says it does
   * @param entries chained entries checked
   * @param unchainedEntries entries written before the chain existed, which nothing vouches for
   * @param brokenAtSequence the first position that failed, or null when intact
   * @param problem what failed there, or null when intact
   * @param headHash the hash the chain ends on: record it elsewhere, and a later verification that
   *     reports a different head for the same length means history was rewritten
   */
  public record AuditVerification(
      boolean intact, long entries, long unchainedEntries, Long brokenAtSequence, String problem,
      String headHash) {}

  private AnalyticsDtos() {}

  public record DailyMetrics(
      LocalDate day,
      int tasksCreated,
      int tasksCompleted,
      int tasksAssigned,
      int commits,
      int repositoriesCreated) {

    public static DailyMetrics from(ProjectDailyMetricsEntity entity) {
      return new DailyMetrics(
          entity.getMetricDate(),
          entity.getTasksCreated(),
          entity.getTasksCompleted(),
          entity.getTasksAssigned(),
          entity.getCommits(),
          entity.getRepositoriesCreated());
    }

    /** A day with no activity. Returned rather than omitted so a chart has no gaps. */
    public static DailyMetrics empty(LocalDate day) {
      return new DailyMetrics(day, 0, 0, 0, 0, 0);
    }
  }

  /**
   * @param days every day in the range, including those with no activity. A client plotting this
   *     should not have to reconstruct the missing dates, and a gap in a series is easily misread
   *     as missing data rather than as a quiet day.
   * @param completeness what this data can and cannot be trusted to show.
   */
  public record ProjectActivity(
      LocalDate from,
      LocalDate to,
      int totalTasksCreated,
      int totalTasksCompleted,
      int totalTasksAssigned,
      int totalCommits,
      List<DailyMetrics> days,
      String completeness) {}
}
