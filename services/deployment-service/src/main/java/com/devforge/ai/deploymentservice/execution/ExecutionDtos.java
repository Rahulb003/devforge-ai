package com.devforge.ai.deploymentservice.execution;

import com.devforge.ai.deploymentservice.sandbox.Sandbox.Language;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class ExecutionDtos {

  private ExecutionDtos() {}

  /** Limits are requests, not orders: the service clamps each to its configured maximum. */
  public record RunRequest(
      @NotNull Language language,
      @NotBlank @Size(max = 65_536) String code,
      Integer timeoutSeconds,
      Integer memoryMb) {}

  public record ExecutionSummary(UUID id, UUID userId, String language, String status,
      Integer exitCode, Long durationMs, Instant createdAt) {

    static ExecutionSummary from(ExecutionEntity e) {
      return new ExecutionSummary(e.getId(), e.getUserId(), e.getLanguage(), e.getStatus().name(),
          e.getExitCode(), e.getDurationMs(), e.getCreatedAt());
    }
  }

  /**
   * One run in full. stdout and stderr are the program's own text - attacker-controlled - and are
   * returned as plain strings for the client to display as text, never to interpret.
   */
  public record ExecutionDetail(UUID id, UUID userId, String language, String code, String status,
      Integer exitCode, String stdout, String stderr, boolean truncated, Long durationMs,
      int timeoutSeconds, int memoryMb, Instant createdAt, Instant finishedAt) {

    static ExecutionDetail from(ExecutionEntity e) {
      return new ExecutionDetail(e.getId(), e.getUserId(), e.getLanguage(), e.getCode(),
          e.getStatus().name(), e.getExitCode(), e.getStdout(), e.getStderr(), e.isTruncated(),
          e.getDurationMs(), e.getTimeoutSeconds(), e.getMemoryMb(), e.getCreatedAt(),
          e.getFinishedAt());
    }
  }
}
