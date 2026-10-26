package com.devforge.ai.deploymentservice.pipeline;

import com.devforge.ai.deploymentservice.sandbox.Sandbox.Language;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * A repository's {@code .devforge/pipeline.yml}.
 *
 * <pre>
 * steps:
 *   - name: test
 *     runtime: python          # python | javascript
 *     run: python -m unittest
 *     timeout: 60              # seconds, optional; clamped to the service's maximum
 * </pre>
 *
 * <p>Parsed with SnakeYAML's SafeConstructor: the file is written by whoever can push to the
 * repository, and YAML's full constructor will instantiate arbitrary types named in a document.
 */
record PipelineDefinition(List<Step> steps) {

  static final String PATH = ".devforge/pipeline.yml";
  static final int MAX_STEPS = 10;
  static final int MAX_SCRIPT_CHARS = 8_192;

  record Step(String name, Language runtime, String run, Integer timeoutSeconds) {}

  static class InvalidPipelineException extends IllegalArgumentException {
    InvalidPipelineException(String message) {
      super(PATH + ": " + message);
    }
  }

  static PipelineDefinition parse(String yaml) {
    var options = new LoaderOptions();
    options.setMaxAliasesForCollections(10);
    options.setCodePointLimit(64 * 1024);
    Object document;
    try {
      document = new Yaml(new SafeConstructor(options)).load(yaml);
    } catch (RuntimeException ex) {
      throw new InvalidPipelineException("not valid YAML");
    }
    if (!(document instanceof Map<?, ?> root) || !(root.get("steps") instanceof List<?> rawSteps)) {
      throw new InvalidPipelineException("expected a top-level 'steps' list");
    }
    if (rawSteps.isEmpty() || rawSteps.size() > MAX_STEPS) {
      throw new InvalidPipelineException("between 1 and " + MAX_STEPS + " steps are allowed");
    }
    var steps = new ArrayList<Step>();
    for (int i = 0; i < rawSteps.size(); i++) {
      if (!(rawSteps.get(i) instanceof Map<?, ?> step)) {
        throw new InvalidPipelineException("step " + (i + 1) + " is not a mapping");
      }
      var name = text(step.get("name"), "step " + (i + 1));
      if (name.isBlank() || name.length() > 60) {
        throw new InvalidPipelineException("step " + (i + 1) + " needs a name of 1 to 60 characters");
      }
      var run = text(step.get("run"), "");
      if (run.isBlank() || run.length() > MAX_SCRIPT_CHARS) {
        throw new InvalidPipelineException("step '" + name + "' needs a 'run' of 1 to "
            + MAX_SCRIPT_CHARS + " characters");
      }
      Language runtime;
      try {
        runtime = Language.valueOf(text(step.get("runtime"), "").toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException ex) {
        throw new InvalidPipelineException("step '" + name + "' needs a runtime: python or javascript");
      }
      Integer timeout = null;
      if (step.get("timeout") != null) {
        if (!(step.get("timeout") instanceof Integer t) || t < 1) {
          throw new InvalidPipelineException("step '" + name + "' has a timeout that is not a positive number");
        }
        timeout = t;
      }
      steps.add(new Step(name.strip(), runtime, run, timeout));
    }
    return new PipelineDefinition(List.copyOf(steps));
  }

  private static String text(Object value, String fallback) {
    return value == null ? fallback : String.valueOf(value);
  }
}
