package com.devforge.ai.deploymentservice.pipeline;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "pipeline_steps")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PipelineStepEntity {

  public enum Status {
    PENDING, RUNNING, SUCCEEDED, FAILED, TIMED_OUT, MEMORY_EXCEEDED, OUTPUT_EXCEEDED,
    /** Not run, because an earlier step did not succeed. */
    SKIPPED,
    REFUSED
  }

  @Id
  private UUID id;

  @Column(nullable = false)
  private UUID runId;

  @Column(nullable = false)
  private int position;

  @Column(nullable = false, length = 60)
  private String name;

  @Column(nullable = false, length = 20)
  private String runtime;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String script;

  @Column(nullable = false)
  private int timeoutSeconds;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Status status;

  private Integer exitCode;

  @Column(columnDefinition = "TEXT")
  private String stdout;

  @Column(columnDefinition = "TEXT")
  private String stderr;

  @Column(nullable = false)
  private boolean truncated;

  private Long durationMs;
}
