package com.devforge.ai.deploymentservice.pipeline;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "pipeline_runs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PipelineRunEntity {

  public enum Status {
    QUEUED, RUNNING, SUCCEEDED, FAILED,
    /** The platform could not run it: the sandbox was off or unreachable. Not the code's fault. */
    ERROR,
    /** The instance running it stopped part-way. */
    INTERRUPTED;

    boolean finished() {
      return this != QUEUED && this != RUNNING;
    }
  }

  @Id
  private UUID id;

  @Column(nullable = false)
  private UUID organizationId;

  @Column(nullable = false)
  private UUID projectId;

  @Column(nullable = false)
  private UUID repositoryId;

  @Column(nullable = false)
  private String ref;

  @Column(nullable = false)
  private UUID triggeredBy;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Status status;

  @Column(length = 2000)
  private String message;

  @Column(columnDefinition = "TEXT")
  private String omittedFiles;

  @Column(nullable = false)
  private Instant createdAt;

  private Instant startedAt;

  private Instant finishedAt;
}
