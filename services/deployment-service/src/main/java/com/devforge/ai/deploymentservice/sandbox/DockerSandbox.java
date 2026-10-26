package com.devforge.ai.deploymentservice.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * A container per run, driven through the docker CLI.
 *
 * <p>The CLI rather than the Engine API because it reaches the daemon the same way on every
 * platform - a Unix socket, a Windows named pipe or a remote DOCKER_HOST - and its arguments go to
 * {@link ProcessBuilder} as a list, never through a shell, so nothing in a request can become a
 * command line. The code itself arrives on stdin: nothing from this host is mounted into a run.
 *
 * <p>Each flag in {@link #runCommand} is there for a guarantee in docs/SANDBOX.md, named beside it,
 * and each guarantee has an escape test in DockerSandboxEscapeTest.
 */
@Slf4j
public class DockerSandbox implements Sandbox {

  /** Exit codes docker itself uses for "could not run the container", as opposed to the program's. */
  private static final Set<Integer> DOCKER_FAILURES = Set.of(125);

  /** 128 + SIGKILL: how a program ended by the in-container time limit exits. */
  private static final int KILLED = 137;

  /**
   * Allowance for the engine on top of a run's own limit. Without it the limit measured the
   * engine as well as the program: the first run after the engine had been idle took over ten
   * seconds to start, and a two-line script was reported as having timed out.
   */
  private static final int ENGINE_GRACE_SECONDS = 20;

  /** Pulling an image is not part of a run's time budget. */
  private static final long PULL_TIMEOUT_SECONDS = 300;

  private final String dockerBinary;
  private final Set<String> pulled = ConcurrentHashMap.newKeySet();

  public DockerSandbox(String dockerBinary) {
    this.dockerBinary = dockerBinary;
  }

  @Override
  public Result run(Language language, String code, Limits limits) {
    ensureImage(language.image);
    var name = "devforge-run-" + UUID.randomUUID();
    var started = System.nanoTime();
    Process process;
    try {
      process = docker(runCommand(name, language, limits)).start();
    } catch (IOException ex) {
      throw new SandboxUnavailableException("The sandbox could not be started: " + ex.getMessage());
    }
    try {
      var exceeded = new AtomicBoolean(false);
      var budget = new AtomicInteger(limits.outputBytes());
      var stdout = new ByteArrayOutputStream();
      var stderr = new ByteArrayOutputStream();
      var outReader = drain(process.getInputStream(), stdout, budget, exceeded, process);
      var errReader = drain(process.getErrorStream(), stderr, budget, exceeded, process);

      try (var stdin = process.getOutputStream()) {
        stdin.write(code.getBytes(StandardCharsets.UTF_8));
      } catch (IOException ex) {
        // The program exited, or was killed, before reading all of its input. Its result says why.
      }

      // The limit itself is enforced inside the container (see runCommand); this wait is the
      // backstop for an engine that hangs, so it allows for the engine's own start-up time.
      var finished = process.waitFor(limits.timeoutSeconds() + ENGINE_GRACE_SECONDS, TimeUnit.SECONDS);
      if (!finished || exceeded.get()) {
        // Killing the docker client does not stop the container; killing the container does, with
        // SIGKILL, which a program cannot trap (G11).
        quietly("kill", name);
        process.waitFor(10, TimeUnit.SECONDS);
        process.destroyForcibly();
      }
      outReader.join(5_000);
      errReader.join(5_000);
      var durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

      var state = inspect(name);
      int exitCode = finished ? process.exitValue() : -1;
      if (finished && !exceeded.get() && DOCKER_FAILURES.contains(exitCode) && state == null) {
        throw new SandboxUnavailableException("The sandbox refused the run: "
            + new String(stderr.toByteArray(), StandardCharsets.UTF_8).strip());
      }

      Outcome outcome;
      if (exceeded.get()) {
        outcome = Outcome.OUTPUT_EXCEEDED;
      } else if (!finished) {
        outcome = Outcome.TIMED_OUT;
      } else if (state != null && state.oomKilled()) {
        outcome = Outcome.MEMORY_EXCEEDED;
      } else if (exitCode == KILLED && durationMs >= limits.timeoutSeconds() * 1000L) {
        outcome = Outcome.TIMED_OUT;
      } else {
        outcome = Outcome.COMPLETED;
      }
      return new Result(outcome, outcome == Outcome.COMPLETED ? exitCode : null,
          stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8),
          exceeded.get(), durationMs);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      quietly("kill", name);
      throw new SandboxUnavailableException("The run was interrupted");
    } finally {
      // Always, whatever happened above: a container left behind would keep its files (G9).
      quietly("rm", "-f", name);
    }
  }

  List<String> runCommand(String name, Language language, Limits limits) {
    var memory = limits.memoryMb() + "m";
    var command = new ArrayList<>(List.of(dockerBinary, "run", "-i", "--name", name,
        "--network", "none",                                  // G1, G3: no route anywhere, metadata included
        "--read-only",                                        // G2: the image cannot be written to
        "--tmpfs", "/work:rw,size=16m,mode=1777",             // G2, G9: the only writable place, gone after
        "--tmpfs", "/tmp:rw,size=16m,mode=1777",
        "--workdir", "/work",
        "--user", "65534:65534",                              // nobody, not root inside the container
        "--cap-drop", "ALL",
        "--security-opt", "no-new-privileges",
        "--memory", memory, "--memory-swap", memory,          // G5: no swap to spill into
        "--cpus", "0.5",                                      // G4
        "--pids-limit", "64",                                 // G8
        "--ulimit", "nofile=256:256",
        "--log-driver", "none",                               // G7: output is never written to disk
        "--label", "devforge.sandbox=run",
        // A real PID 1. The time limit's kill comes from inside the container, and PID 1 there
        // ignores any signal it has no handler for - SIGKILL included - so without an init the
        // program sat in that slot and the limit silently never fired.
        "--init",
        language.image,
        "timeout", "-s", "KILL", String.valueOf(limits.timeoutSeconds()))); // G6, G11
    command.addAll(List.of(language.command));
    return command;
  }

  /**
   * The docker client gets this service's PATH and its DOCKER_* settings, and nothing else. The
   * service's environment holds database and signing credentials; none of them has any business
   * near a run, even though the client would not forward them unasked (G3).
   */
  private ProcessBuilder docker(List<String> command) {
    var builder = new ProcessBuilder(command);
    Map<String, String> env = builder.environment();
    env.keySet().removeIf(key -> !key.startsWith("DOCKER_") && !Set.of("PATH", "Path", "HOME",
        "USERPROFILE", "SystemRoot", "TEMP", "TMP", "LOCALAPPDATA", "APPDATA", "ProgramData")
        .contains(key));
    return builder;
  }

  private void ensureImage(String image) {
    if (pulled.contains(image)) {
      return;
    }
    if (exec(30, "image", "inspect", "--format", "{{.Id}}", image) != 0
        && exec(PULL_TIMEOUT_SECONDS, "pull", "--quiet", image) != 0) {
      throw new SandboxUnavailableException("The sandbox cannot reach its container engine");
    }
    pulled.add(image);
  }

  private record State(boolean oomKilled) {}

  private State inspect(String name) {
    try {
      var process = docker(List.of(dockerBinary, "inspect", "--format", "{{.State.OOMKilled}}", name))
          .redirectErrorStream(true).start();
      var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
      if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
        return null;
      }
      return new State(Boolean.parseBoolean(out));
    } catch (IOException ex) {
      return null;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  private int exec(long timeoutSeconds, String... args) {
    var command = new ArrayList<String>();
    command.add(dockerBinary);
    command.addAll(List.of(args));
    try {
      var process = docker(command).redirectErrorStream(true)
          .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
      if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return -1;
      }
      return process.exitValue();
    } catch (IOException ex) {
      return -1;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return -1;
    }
  }

  private void quietly(String... args) {
    if (exec(30, args) != 0 && "rm".equals(args[0])) {
      log.warn("Could not remove sandbox container {}", args[args.length - 1]);
    }
  }

  /**
   * Copies a stream until the shared output budget runs out, then flags the run for killing (G7).
   * Shared between stdout and stderr, so a program cannot double its allowance by using both.
   */
  private static Thread drain(InputStream in, ByteArrayOutputStream sink, AtomicInteger budget,
      AtomicBoolean exceeded, Process process) {
    var thread = new Thread(() -> {
      var buffer = new byte[8192];
      try (in) {
        int read;
        while ((read = in.read(buffer)) != -1) {
          int allowed = Math.min(read, Math.max(budget.getAndAdd(-read), 0));
          sink.write(buffer, 0, allowed);
          if (allowed < read) {
            exceeded.set(true);
            process.destroyForcibly();
            return;
          }
        }
      } catch (IOException ex) {
        // The process was killed; what was read so far is the output.
      }
    }, "sandbox-output");
    thread.setDaemon(true);
    thread.start();
    return thread;
  }
}
