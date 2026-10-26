package com.devforge.ai.deploymentservice.pipeline;

import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PipelineDtos {

  private PipelineDtos() {}

  /** Without a ref, the repository's own default branch. */
  public record TriggerRequest(@Size(max = 255) String ref) {}

  public record RunSummary(UUID id, String ref, String status, UUID triggeredBy, Instant createdAt,
      Instant finishedAt) {

    static RunSummary from(PipelineRunEntity r) {
      return new RunSummary(r.getId(), r.getRef(), r.getStatus().name(), r.getTriggeredBy(),
          r.getCreatedAt(), r.getFinishedAt());
    }
  }

  /** Step output is the program's own text: display it as text, never interpret it. */
  public record StepView(int position, String name, String runtime, String script, int timeoutSeconds,
      String status, Integer exitCode, String stdout, String stderr, boolean truncated,
      Long durationMs) {

    static StepView from(PipelineStepEntity s) {
      return new StepView(s.getPosition(), s.getName(), s.getRuntime(), s.getScript(),
          s.getTimeoutSeconds(), s.getStatus().name(), s.getExitCode(), s.getStdout(), s.getStderr(),
          s.isTruncated(), s.getDurationMs());
    }
  }

  public record RunDetail(UUID id, UUID repositoryId, String ref, String status, String message,
      List<String> omittedFiles, UUID triggeredBy, Instant createdAt, Instant startedAt,
      Instant finishedAt, List<StepView> steps) {

    static RunDetail from(PipelineRunEntity r, List<PipelineStepEntity> steps) {
      var omitted = r.getOmittedFiles() == null || r.getOmittedFiles().isEmpty()
          ? List.<String>of()
          : List.of(r.getOmittedFiles().split("\n"));
      return new RunDetail(r.getId(), r.getRepositoryId(), r.getRef(), r.getStatus().name(),
          r.getMessage(), omitted, r.getTriggeredBy(), r.getCreatedAt(), r.getStartedAt(),
          r.getFinishedAt(), steps.stream().map(StepView::from).toList());
    }
  }
}
