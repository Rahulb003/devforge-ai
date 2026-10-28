package com.devforge.ai.deploymentservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import com.devforge.ai.common.security.client.ProjectAccessClient;
import com.devforge.ai.deploymentservice.execution.ExecutionEntity.Status;
import com.devforge.ai.deploymentservice.execution.ExecutionRepository;
import com.devforge.ai.deploymentservice.sandbox.Sandbox;
import com.devforge.ai.deploymentservice.sandbox.Sandbox.Language;
import com.devforge.ai.deploymentservice.sandbox.Sandbox.Limits;
import com.devforge.ai.deploymentservice.sandbox.Sandbox.Outcome;
import com.devforge.ai.deploymentservice.sandbox.Sandbox.Result;
import com.devforge.ai.deploymentservice.sandbox.Sandbox.SandboxUnavailableException;
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
 * The execution API around the sandbox: who may run, what is recorded, what is clamped, and what
 * happens when the sandbox is not there. The sandbox itself is attacked in DockerSandboxEscapeTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Execution API")
class ExecutionApiTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ExecutionRepository executions;
  @MockitoBean private ProjectAccessClient projectAccessClient;
  @MockitoBean private Sandbox sandbox;

  private final UUID organizationId = UUID.randomUUID();
  private final UUID projectId = UUID.randomUUID();
  private final UUID alice = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    executions.deleteAll();
  }

  private String base() {
    return "/api/v1/organizations/" + organizationId + "/projects/" + projectId + "/executions";
  }

  private ResultActions run(String body) throws Exception {
    return mockMvc.perform(post(base())
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.accessToken(alice))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body));
  }

  @Test
  @DisplayName("runs code, returns the result and records who ran what")
  void runsAndAudits() throws Exception {
    when(sandbox.run(eq(Language.PYTHON), eq("print(1)"), any()))
        .thenReturn(new Result(Outcome.COMPLETED, 0, "1\n", "", false, 120));

    var json = run("{\"language\":\"PYTHON\",\"code\":\"print(1)\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.status").value("COMPLETED"))
        .andExpect(jsonPath("$.data.stdout").value("1\n"))
        .andExpect(jsonPath("$.data.exitCode").value(0))
        .andReturn().getResponse().getContentAsString();

    var row = executions.findAll().get(0);
    assertThat(row.getUserId()).isEqualTo(alice);
    assertThat(row.getProjectId()).isEqualTo(projectId);
    assertThat(row.getCode()).isEqualTo("print(1)");
    assertThat(row.getStatus()).isEqualTo(Status.COMPLETED);
    assertThat(json).contains(row.getId().toString());
    verify(projectAccessClient).requireProjectAccess(eq(organizationId), eq(projectId), any(),
        eq(ProjectAccessClient.Access.WRITE));

    mockMvc.perform(get(base()).header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.accessToken(alice)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].code").doesNotExist());
  }

  @Test
  @DisplayName("asking for more time or memory than allowed gets the maximum")
  void clampsLimits() throws Exception {
    var limits = ArgumentCaptor.forClass(Limits.class);
    when(sandbox.run(any(), any(), limits.capture()))
        .thenReturn(new Result(Outcome.COMPLETED, 0, "", "", false, 1));

    run("{\"language\":\"PYTHON\",\"code\":\"x\",\"timeoutSeconds\":600,\"memoryMb\":99999}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.timeoutSeconds").value(10))
        .andExpect(jsonPath("$.data.memoryMb").value(256));
    assertThat(limits.getValue()).isEqualTo(new Limits(10, 256, 65536));
  }

  @Test
  @DisplayName("a non-member gets 404 and nothing runs")
  void nonMemberIsNotFound() throws Exception {
    doThrow(new ResourceNotFoundException("Project not found"))
        .when(projectAccessClient).requireProjectAccess(eq(organizationId), eq(projectId), any(), any());

    run("{\"language\":\"PYTHON\",\"code\":\"print(1)\"}").andExpect(status().isNotFound());
    verify(sandbox, never()).run(any(), any(), any());
    assertThat(executions.count()).isZero();
  }

  @Test
  @DisplayName("G12: no sandbox means 503, the refusal is recorded, and nothing ran")
  void refusedWhenSandboxUnavailable() throws Exception {
    when(sandbox.run(any(), any(), any()))
        .thenThrow(new SandboxUnavailableException("Code execution is not enabled on this deployment"));

    run("{\"language\":\"PYTHON\",\"code\":\"print(1)\"}")
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.message").value("Code execution is not enabled on this deployment"));
    assertThat(executions.findAll()).singleElement()
        .satisfies(row -> assertThat(row.getStatus()).isEqualTo(Status.REFUSED));
  }

  @Test
  @DisplayName("the hourly quota is enforced, refused runs included")
  void quota() throws Exception {
    when(sandbox.run(any(), any(), any()))
        .thenThrow(new SandboxUnavailableException("down"));
    for (int i = 0; i < 3; i++) {
      run("{\"language\":\"PYTHON\",\"code\":\"x\"}").andExpect(status().isServiceUnavailable());
    }
    run("{\"language\":\"PYTHON\",\"code\":\"x\"}").andExpect(status().isTooManyRequests());
  }

  private void existingRun(java.time.Instant at) {
    executions.save(com.devforge.ai.deploymentservice.execution.ExecutionEntity.builder()
        .id(UUID.randomUUID()).organizationId(organizationId).projectId(projectId).userId(alice)
        .language("PYTHON").code("x").timeoutSeconds(10).memoryMb(256).outputBytes(65536)
        .status(Status.COMPLETED).createdAt(at).build());
  }

  @Test
  @DisplayName("a run over the quota is refused and leaves no row behind")
  void overQuotaLeavesNothing() throws Exception {
    var earlier = java.time.Instant.now().minusSeconds(60);
    for (int i = 0; i < 3; i++) existingRun(earlier.plusSeconds(i));

    run("{\"language\":\"PYTHON\",\"code\":\"x\"}").andExpect(status().isTooManyRequests());
    assertThat(executions.count()).isEqualTo(3);
    verify(sandbox, never()).run(any(), any(), any());
  }

  @Test
  @DisplayName("the quota counts runs up to this one, so a simultaneous later request cannot crowd it out")
  void quotaCountsOnlyEarlierRuns() throws Exception {
    when(sandbox.run(any(), any(), any())).thenReturn(new Result(Outcome.COMPLETED, 0, "", "", false, 1));
    var now = java.time.Instant.now();
    existingRun(now.minusSeconds(30));
    // A request that raced this one and was written a moment after it: it is the one that must
    // yield, so it does not count against this run.
    existingRun(now.plusSeconds(5));

    run("{\"language\":\"PYTHON\",\"code\":\"x\"}").andExpect(status().isCreated());
  }

  @Test
  @DisplayName("rejects an unknown language and empty code")
  void validates() throws Exception {
    run("{\"language\":\"COBOL\",\"code\":\"x\"}").andExpect(status().isBadRequest());
    run("{\"language\":\"PYTHON\",\"code\":\"\"}").andExpect(status().isBadRequest());
    verify(sandbox, never()).run(any(), any(), any());
  }

  @Test
  @DisplayName("another project's run is not found through this project")
  void otherProjectsRunIsNotFound() throws Exception {
    when(sandbox.run(any(), any(), any())).thenReturn(new Result(Outcome.COMPLETED, 0, "", "", false, 1));
    run("{\"language\":\"PYTHON\",\"code\":\"x\"}").andExpect(status().isCreated());
    var id = executions.findAll().get(0).getId();

    var otherProject = UUID.randomUUID();
    mockMvc.perform(get("/api/v1/organizations/" + organizationId + "/projects/" + otherProject
            + "/executions/" + id)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.accessToken(alice)))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("needs a signed-in caller")
  void needsAuthentication() throws Exception {
    mockMvc.perform(post(base()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"language\":\"PYTHON\",\"code\":\"x\"}"))
        .andExpect(status().isUnauthorized());
  }
}
