package com.devforge.ai.deploymentservice.execution;

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
@Table(name = "executions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionEntity {

  public enum Status { RUNNING, COMPLETED, TIMED_OUT, MEMORY_EXCEEDED, OUTPUT_EXCEEDED, REFUSED }

  /** Assigned here, not generated: the id names the run before the row is first saved. */
  @Id
  private UUID id;

  @Column(nullable = false)
  private UUID organizationId;

  @Column(nullable = false)
  private UUID projectId;

  @Column(nullable = false)
  private UUID userId;

  @Column(nullable = false, length = 20)
  private String language;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String code;

  @Column(nullable = false)
  private int timeoutSeconds;

  @Column(nullable = false)
  private int memoryMb;

  @Column(nullable = false)
  private int outputBytes;

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

  @Column(nullable = false)
  private Instant createdAt;

  private Instant finishedAt;
}
