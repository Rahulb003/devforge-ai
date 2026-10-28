package com.devforge.ai.deploymentservice.pipeline;

import com.devforge.ai.common.exception.ResourceNotFoundException;
import com.devforge.ai.common.git.GitContentClient;
import com.devforge.ai.common.security.AuthenticatedUser;
import com.devforge.ai.common.security.client.ProjectAccessClient;
import com.devforge.ai.deploymentservice.execution.ExecutionService.QuotaExceededException;
import com.devforge.ai.deploymentservice.pipeline.PipelineDtos.RunDetail;
import com.devforge.ai.deploymentservice.pipeline.PipelineDtos.RunSummary;
import com.devforge.ai.deploymentservice.sandbox.Sandbox;
import com.devforge.ai.deploymentservice.sandbox.Sandbox.Limits;
import com.devforge.ai.deploymentservice.sandbox.Sandbox.SandboxUnavailableException;
import com.devforge.ai.deploymentservice.sandbox.Workspace;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Pipelines: the steps a repository defines, run in the sandbox against the repository at one ref.
 *
 * <p>Everything that needs the caller - membership, the quota, reading the definition and the
 * repository - happens in the request, with the caller's own token. Only running the steps happens
 * afterwards, on a bounded executor, and it needs nothing from the caller: no token is kept
 * beyond the request that brought it.
 *
 * <p>Like code runs, not {@code @Transactional}: a run lasts minutes, and each save is its own
 * short transaction.
 */
@Slf4j
@Service
public class PipelineService {

  private static final int MAX_PAGE = 50;

  private final PipelineRunRepository runs;
  private final PipelineStepRepository steps;
  private final ProjectAccessClient projectAccess;
  private final GitContentClient gitContent;
  private final Sandbox sandbox;
  private final int maxStepSeconds;
  private final int stepMemoryMb;
  private final int stepOutputBytes;
  private final int runsPerHour;
  private final long maxWorkspaceBytes;
  private final ThreadPoolExecutor executor;

  public PipelineService(PipelineRunRepository runs, PipelineStepRepository steps,
      ProjectAccessClient projectAccess, GitContentClient gitContent, Sandbox sandbox,
      @Value("${devforge.pipelines.max-step-seconds:120}") int maxStepSeconds,
      @Value("${devforge.pipelines.step-memory-mb:512}") int stepMemoryMb,
      @Value("${devforge.pipelines.step-output-bytes:262144}") int stepOutputBytes,
      @Value("${devforge.pipelines.runs-per-hour:10}") int runsPerHour,
      @Value("${devforge.pipelines.concurrent-runs:2}") int concurrentRuns,
      @Value("${devforge.pipelines.queue-size:20}") int queueSize,
      @Value("${devforge.pipelines.max-workspace-bytes:16777216}") long maxWorkspaceBytes) {
    this.runs = runs;
    this.steps = steps;
    this.projectAccess = projectAccess;
    this.gitContent = gitContent;
    this.sandbox = sandbox;
    this.maxStepSeconds = maxStepSeconds;
    this.stepMemoryMb = stepMemoryMb;
    this.stepOutputBytes = stepOutputBytes;
    this.runsPerHour = runsPerHour;
    this.maxWorkspaceBytes = maxWorkspaceBytes;
    // Bounded both ways: a burst of triggers waits in a short queue and is then refused, rather
    // than growing a backlog nobody is waiting for any more.
    this.executor = new ThreadPoolExecutor(concurrentRuns, concurrentRuns, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(queueSize), r -> {
          var thread = new Thread(r, "pipeline-runner");
          thread.setDaemon(true);
          return thread;
        });
  }

  @PreDestroy
  void shutdown() {
    executor.shutdownNow();
  }

  public RunDetail trigger(UUID organizationId, UUID projectId, UUID repositoryId, String requestedRef) {
    var token = bearerToken();
    projectAccess.requireProjectAccess(organizationId, projectId, token, ProjectAccessClient.Access.WRITE);
    var user = currentUser();
    // A fast refusal before reading the repository; the check that holds under concurrency is
    // the one after this run's row is written, below.
    if (runs.countByTriggeredByAndCreatedAtAfterAndCreatedAtLessThanEqual(user.id(),
        Instant.now().minus(Duration.ofHours(1)), Instant.now()) >= runsPerHour) {
      throw new QuotaExceededException("Pipeline limit reached: " + runsPerHour + " runs per hour");
    }

    var context = new GitContentClient.Context(organizationId, projectId, repositoryId);
    var ref = requestedRef == null || requestedRef.isBlank()
        ? gitContent.defaultBranch(context, token)
        : requestedRef.strip();
    PipelineDefinition definition;
    try {
      definition = PipelineDefinition.parse(gitContent.file(context, ref, PipelineDefinition.PATH, token).content());
    } catch (ResourceNotFoundException ex) {
      throw new IllegalArgumentException("No pipeline is defined at " + ref + ": add "
          + PipelineDefinition.PATH + " to the repository");
    }

    var files = new LinkedHashMap<String, String>();
    var omitted = new ArrayList<String>();
    long workspaceBytes = 0;
    for (var file : gitContent.filesToAnalyse(context, ref, null, token)) {
      // Held in memory until the run's steps finish, and up to queue-size runs can wait, so the
      // workspace is capped: uncapped, twenty queued runs of a large repository were gigabytes.
      var bytes = (long) file.content().length() * 3;
      if (file.binary() || file.truncated() || !Workspace.isSafe(file.path())
          || workspaceBytes + bytes > maxWorkspaceBytes) {
        omitted.add(file.path());
      } else {
        files.put(file.path(), file.content());
        workspaceBytes += bytes;
      }
    }

    var run = runs.save(PipelineRunEntity.builder()
        .id(UUID.randomUUID())
        .organizationId(organizationId)
        .projectId(projectId)
        .repositoryId(repositoryId)
        .ref(ref)
        .triggeredBy(user.id())
        .status(PipelineRunEntity.Status.QUEUED)
        .omittedFiles(omitted.isEmpty() ? null : String.join("\n", omitted))
        // Microseconds, as the column stores them: it rounds, so a nanosecond Instant could be
        // stored just after itself and the quota count, which compares against it, missed this row.
        .createdAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
        .build());
    // Counted after this run exists, and only runs at or before it, so of two simultaneous
    // triggers the later always sees the earlier: the limit cannot be overrun by a race.
    if (runs.countByTriggeredByAndCreatedAtAfterAndCreatedAtLessThanEqual(user.id(),
        run.getCreatedAt().minus(Duration.ofHours(1)), run.getCreatedAt()) > runsPerHour) {
      runs.delete(run);
      throw new QuotaExceededException("Pipeline limit reached: " + runsPerHour + " runs per hour");
    }
    var stepRows = new ArrayList<PipelineStepEntity>();
    for (int i = 0; i < definition.steps().size(); i++) {
      var step = definition.steps().get(i);
      stepRows.add(steps.save(PipelineStepEntity.builder()
          .id(UUID.randomUUID())
          .runId(run.getId())
          .position(i + 1)
          .name(step.name())
          .runtime(step.runtime().name())
          .script(step.run())
          .timeoutSeconds(step.timeoutSeconds() == null
              ? maxStepSeconds : Math.min(step.timeoutSeconds(), maxStepSeconds))
          .status(PipelineStepEntity.Status.PENDING)
          .build()));
    }

    try {
      executor.execute(() -> execute(run, stepRows, Map.copyOf(files)));
    } catch (RejectedExecutionException ex) {
      finish(run, PipelineRunEntity.Status.ERROR, "Too many pipelines are queued; try again shortly");
      skipFrom(stepRows, 0);
      throw new QuotaExceededException("Too many pipelines are queued; try again shortly");
    }
    return RunDetail.from(run, stepRows);
  }

  /** Runs the steps in order and stops at the first that does not succeed, like any CI. */
  void execute(PipelineRunEntity run, List<PipelineStepEntity> stepRows, Map<String, String> files) {
    run.setStatus(PipelineRunEntity.Status.RUNNING);
    run.setStartedAt(Instant.now());
    runs.save(run);
    try {
      for (int i = 0; i < stepRows.size(); i++) {
        var step = stepRows.get(i);
        step.setStatus(PipelineStepEntity.Status.RUNNING);
        steps.save(step);
        Sandbox.Result result;
        try {
          result = sandbox.runStep(Sandbox.Language.valueOf(step.getRuntime()), files, step.getScript(),
              new Limits(step.getTimeoutSeconds(), stepMemoryMb, stepOutputBytes));
        } catch (SandboxUnavailableException ex) {
          step.setStatus(PipelineStepEntity.Status.REFUSED);
          step.setStderr(ex.getMessage());
          steps.save(step);
          skipFrom(stepRows, i + 1);
          finish(run, PipelineRunEntity.Status.ERROR, ex.getMessage());
          return;
        }
        step.setExitCode(result.exitCode());
        step.setStdout(result.stdout());
        step.setStderr(result.stderr());
        step.setTruncated(result.truncated());
        step.setDurationMs(result.durationMs());
        step.setStatus(switch (result.outcome()) {
          case COMPLETED -> result.exitCode() != null && result.exitCode() == 0
              ? PipelineStepEntity.Status.SUCCEEDED : PipelineStepEntity.Status.FAILED;
          case TIMED_OUT -> PipelineStepEntity.Status.TIMED_OUT;
          case MEMORY_EXCEEDED -> PipelineStepEntity.Status.MEMORY_EXCEEDED;
          case OUTPUT_EXCEEDED -> PipelineStepEntity.Status.OUTPUT_EXCEEDED;
        });
        steps.save(step);
        if (step.getStatus() != PipelineStepEntity.Status.SUCCEEDED) {
          skipFrom(stepRows, i + 1);
          finish(run, PipelineRunEntity.Status.FAILED, "Step '" + step.getName() + "' did not succeed");
          return;
        }
      }
      finish(run, PipelineRunEntity.Status.SUCCEEDED, null);
    } catch (RuntimeException ex) {
      log.error("Pipeline run {} failed unexpectedly", run.getId(), ex);
      finish(run, PipelineRunEntity.Status.ERROR, "The pipeline could not be run");
    }
  }

  public List<RunSummary> list(UUID organizationId, UUID projectId, UUID repositoryId) {
    projectAccess.requireProjectAccess(organizationId, projectId, bearerToken(), ProjectAccessClient.Access.READ);
    interruptStale();
    return runs.findByRepositoryIdAndProjectIdOrderByCreatedAtDesc(repositoryId, projectId,
        PageRequest.of(0, MAX_PAGE)).stream().map(RunSummary::from).toList();
  }

  public RunDetail get(UUID organizationId, UUID projectId, UUID repositoryId, UUID runId) {
    projectAccess.requireProjectAccess(organizationId, projectId, bearerToken(), ProjectAccessClient.Access.READ);
    interruptStale();
    var run = runs.findByIdAndRepositoryIdAndProjectId(runId, repositoryId, projectId)
        .orElseThrow(() -> new ResourceNotFoundException("Pipeline run not found"));
    return RunDetail.from(run, steps.findByRunIdOrderByPosition(run.getId()));
  }

  /** No run outlives every step at its maximum, plus a margin for queueing and the engine. */
  private void interruptStale() {
    var longest = Duration.ofSeconds((long) maxStepSeconds * PipelineDefinition.MAX_STEPS)
        .plusMinutes(30);
    runs.interruptStale(Instant.now().minus(longest), Instant.now());
  }

  private void skipFrom(List<PipelineStepEntity> stepRows, int from) {
    for (int i = from; i < stepRows.size(); i++) {
      stepRows.get(i).setStatus(PipelineStepEntity.Status.SKIPPED);
      steps.save(stepRows.get(i));
    }
  }

  private void finish(PipelineRunEntity run, PipelineRunEntity.Status status, String message) {
    run.setStatus(status);
    run.setMessage(message);
    run.setFinishedAt(Instant.now());
    runs.save(run);
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
