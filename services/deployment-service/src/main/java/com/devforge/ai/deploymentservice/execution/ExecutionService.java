package com.devforge.ai.deploymentservice.execution;

import com.devforge.ai.common.exception.ResourceNotFoundException;
import com.devforge.ai.common.security.AuthenticatedUser;
import com.devforge.ai.common.security.client.ProjectAccessClient;
import com.devforge.ai.deploymentservice.execution.ExecutionDtos.ExecutionDetail;
import com.devforge.ai.deploymentservice.execution.ExecutionDtos.ExecutionSummary;
import com.devforge.ai.deploymentservice.execution.ExecutionDtos.RunRequest;
import com.devforge.ai.deploymentservice.execution.ExecutionEntity.Status;
import com.devforge.ai.deploymentservice.sandbox.Sandbox;
import com.devforge.ai.deploymentservice.sandbox.Sandbox.Limits;
import com.devforge.ai.deploymentservice.sandbox.Sandbox.SandboxUnavailableException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Running code for a project member, inside the sandbox.
 *
 * <p>Deliberately not {@code @Transactional}: a run lasts seconds, and holding a connection and a
 * transaction open for that long would let a few slow programs exhaust the pool. The audit row is
 * saved before the run and again after it, each in its own short transaction.
 */
@Service
public class ExecutionService {

  private static final int MAX_PAGE = 100;

  public static class QuotaExceededException extends RuntimeException {
    public QuotaExceededException(String message) {
      super(message);
    }
  }

  private final ExecutionRepository executions;
  private final ProjectAccessClient projectAccess;
  private final Sandbox sandbox;
  private final Limits ceiling;
  private final int runsPerHour;
  private final Semaphore slots;

  public ExecutionService(ExecutionRepository executions, ProjectAccessClient projectAccess,
      Sandbox sandbox,
      @Value("${devforge.sandbox.max-timeout-seconds:10}") int maxTimeoutSeconds,
      @Value("${devforge.sandbox.max-memory-mb:256}") int maxMemoryMb,
      @Value("${devforge.sandbox.max-output-bytes:65536}") int maxOutputBytes,
      @Value("${devforge.sandbox.runs-per-hour:30}") int runsPerHour,
      @Value("${devforge.sandbox.concurrent-runs:4}") int concurrentRuns) {
    this.executions = executions;
    this.projectAccess = projectAccess;
    this.sandbox = sandbox;
    this.ceiling = new Limits(maxTimeoutSeconds, maxMemoryMb, maxOutputBytes);
    this.runsPerHour = runsPerHour;
    this.slots = new Semaphore(concurrentRuns);
  }

  public ExecutionDetail run(UUID organizationId, UUID projectId, RunRequest request) {
    projectAccess.requireProjectAccess(organizationId, projectId, bearerToken(),
        ProjectAccessClient.Access.WRITE);
    var user = currentUser();
    if (!slots.tryAcquire()) {
      throw new QuotaExceededException("The sandbox is busy; try again in a moment");
    }
    try {
      var limits = clamp(request);
      var execution = executions.save(ExecutionEntity.builder()
          .id(UUID.randomUUID())
          .organizationId(organizationId)
          .projectId(projectId)
          .userId(user.id())
          .language(request.language().name())
          .code(request.code())
          .timeoutSeconds(limits.timeoutSeconds())
          .memoryMb(limits.memoryMb())
          .outputBytes(limits.outputBytes())
          .status(Status.RUNNING)
          // Microseconds, as the column stores them: it rounds, so a nanosecond Instant could be
          // stored just after itself and the quota count, which compares against it, missed this row.
          .createdAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
          .build());
      // The quota is checked after this run's row exists, not before: two requests checking first
      // could both see room for one more and both run. Counted from the audit table, so it holds
      // across instances and restarts, and counting only runs at or before this one means the
      // later of two simultaneous requests always sees the earlier. Refused runs count too:
      // otherwise a caller could probe a down sandbox as fast as it liked.
      if (executions.countByUserIdAndCreatedAtAfterAndCreatedAtLessThanEqual(user.id(),
          execution.getCreatedAt().minus(Duration.ofHours(1)), execution.getCreatedAt()) > runsPerHour) {
        executions.delete(execution);
        throw new QuotaExceededException("Run limit reached: " + runsPerHour + " per hour");
      }
      try {
        var result = sandbox.run(request.language(), request.code(), limits);
        execution.setStatus(Status.valueOf(result.outcome().name()));
        execution.setExitCode(result.exitCode());
        execution.setStdout(result.stdout());
        execution.setStderr(result.stderr());
        execution.setTruncated(result.truncated());
        execution.setDurationMs(result.durationMs());
      } catch (SandboxUnavailableException ex) {
        execution.setStatus(Status.REFUSED);
        execution.setStderr(ex.getMessage());
        execution.setFinishedAt(Instant.now());
        executions.save(execution);
        throw ex;
      }
      execution.setFinishedAt(Instant.now());
      return ExecutionDetail.from(executions.save(execution));
    } finally {
      slots.release();
    }
  }

  public List<ExecutionSummary> list(UUID organizationId, UUID projectId, int limit) {
    projectAccess.requireProjectAccess(organizationId, projectId, bearerToken(),
        ProjectAccessClient.Access.READ);
    return executions.findByProjectIdOrderByCreatedAtDesc(projectId,
            PageRequest.of(0, Math.min(Math.max(limit, 1), MAX_PAGE)))
        .stream().map(ExecutionSummary::from).toList();
  }

  public ExecutionDetail get(UUID organizationId, UUID projectId, UUID executionId) {
    projectAccess.requireProjectAccess(organizationId, projectId, bearerToken(),
        ProjectAccessClient.Access.READ);
    // Looked up within the project: another project's run id is simply not found here.
    return executions.findByIdAndProjectId(executionId, projectId).map(ExecutionDetail::from)
        .orElseThrow(() -> new ResourceNotFoundException("Execution not found"));
  }

  /** A client asking for more than the ceiling gets the ceiling, not an error and not its wish. */
  Limits clamp(RunRequest request) {
    return new Limits(
        clamp(request.timeoutSeconds(), ceiling.timeoutSeconds()),
        clamp(request.memoryMb(), ceiling.memoryMb()),
        ceiling.outputBytes());
  }

  private static int clamp(Integer requested, int max) {
    return requested == null ? max : Math.max(1, Math.min(requested, max));
  }

  private AuthenticatedUser currentUser() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
      return user;
    }
    throw new AccessDeniedException("Not authenticated");
  }

  private String bearerToken() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
      var header = attrs.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
      if (header != null && !header.isBlank()) {
        return header;
      }
    }
    throw new AccessDeniedException("No bearer token on the current request");
  }
}
