package com.devforge.ai.deploymentservice.sandbox;

/**
 * Runs untrusted code somewhere it cannot hurt anything. See docs/SANDBOX.md for what an
 * implementation must guarantee, and the escape tests that hold it to that.
 *
 * <p>An implementation that cannot isolate a run throws {@link SandboxUnavailableException}. It
 * never falls back to running the code some other way (G12).
 */
public interface Sandbox {

  Result run(Language language, String code, Limits limits);

  /**
   * Runs a pipeline step: {@code script} under {@code sh}, in a work directory holding
   * {@code files} (repository path to text content). Same isolation as {@link #run}.
   */
  Result runStep(Language runtime, java.util.Map<String, String> files, String script, Limits limits);

  enum Language {
    PYTHON("python:3.12-alpine", "python3", "-u", "-"),
    JAVASCRIPT("node:22-alpine", "node", "-");

    final String image;
    final String[] command;

    Language(String image, String... command) {
      this.image = image;
      this.command = command;
    }
  }

  record Limits(int timeoutSeconds, int memoryMb, int outputBytes) {}

  enum Outcome {
    /** The program ran to its own end; see the exit code. */
    COMPLETED,
    TIMED_OUT,
    MEMORY_EXCEEDED,
    /** Killed for writing more than the output limit. */
    OUTPUT_EXCEEDED
  }

  record Result(Outcome outcome, Integer exitCode, String stdout, String stderr, boolean truncated,
      long durationMs) {}

  class SandboxUnavailableException extends RuntimeException {
    public SandboxUnavailableException(String message) {
      super(message);
    }
  }
}
