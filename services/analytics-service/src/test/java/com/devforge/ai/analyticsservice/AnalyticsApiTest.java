package com.devforge.ai.analyticsservice;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devforge.ai.analyticsservice.entity.ProjectDailyMetricsEntity;
import com.devforge.ai.analyticsservice.repository.ProjectDailyMetricsRepository;
import com.devforge.ai.common.exception.ResourceNotFoundException;
import com.devforge.ai.common.security.client.ProjectAccessClient;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Reading the aggregated metrics, and who may read them. */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Analytics API")
class AnalyticsApiTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ProjectDailyMetricsRepository metrics;
  @Autowired private com.devforge.ai.analyticsservice.repository.AuditEntryRepository auditEntries;

  @Autowired private com.devforge.ai.analyticsservice.repository.AuditChainHeadRepository auditChainHeads;
  @Autowired private com.devforge.ai.analyticsservice.consumer.AuditRecorder auditRecorder;
  @Autowired private org.springframework.transaction.support.TransactionTemplate transactionTemplate;
  @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

  @MockitoBean private ProjectAccessClient projectAccessClient;

  private final UUID organizationId = UUID.randomUUID();
  private final UUID projectId = UUID.randomUUID();
  private final UUID user = UUID.randomUUID();

  private String base() {
    return "/api/v1/organizations/" + organizationId + "/projects/" + projectId + "/analytics";
  }

  private String bearer() {
    return "Bearer " + TestTokens.accessToken(user);
  }

  private LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  @BeforeEach
  void setUp() {
    metrics.deleteAll();
    auditEntries.deleteAll();
    auditChainHeads.deleteAll();
  }

  private void store(LocalDate day, int created, int completed, int commits) {
    metrics.save(ProjectDailyMetricsEntity.builder()
        .projectId(projectId)
        .organizationId(organizationId)
        .metricDate(day)
        .tasksCreated(created)
        .tasksCompleted(completed)
        .commits(commits)
        .build());
  }

  @Nested
  @DisplayName("reading activity")
  class Reading {

    @Test
    @DisplayName("totals the range and returns a day per date")
    void totalsAndSeries() throws Exception {
      store(today(), 3, 1, 5);
      store(today().minusDays(2), 2, 2, 1);

      mockMvc.perform(get(base() + "?from=" + today().minusDays(2) + "&to=" + today())
              .header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.totalTasksCreated").value(5))
          .andExpect(jsonPath("$.data.totalTasksCompleted").value(3))
          .andExpect(jsonPath("$.data.totalCommits").value(6))
          // Three days requested, three days returned — including the quiet one in the middle.
          .andExpect(jsonPath("$.data.days.length()").value(3))
          .andExpect(jsonPath("$.data.days[1].tasksCreated").value(0));
    }

    @Test
    @DisplayName("states what the numbers can and cannot be trusted to show")
    void statesCompleteness() throws Exception {
      mockMvc.perform(get(base()).header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isOk())
          // Counts derived from events say nothing about activity from before this service
          // existed, and a reader should not have to work that out.
          .andExpect(jsonPath("$.data.completeness").value(
              org.hamcrest.Matchers.containsString("before analytics-service")));
    }

    @Test
    @DisplayName("defaults to the last 30 days")
    void defaultsToThirtyDays() throws Exception {
      mockMvc.perform(get(base()).header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.days.length()").value(30));
    }

    @Test
    @DisplayName("a project with no recorded activity returns zeros, not an error")
    void noActivityIsZeroNotAnError() throws Exception {
      mockMvc.perform(get(base()).header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.totalTasksCreated").value(0));
    }

    @Test
    @DisplayName("another project's metrics are not counted")
    void metricsAreScopedToTheProject() throws Exception {
      store(today(), 3, 1, 5);
      metrics.save(ProjectDailyMetricsEntity.builder()
          .projectId(UUID.randomUUID())
          .organizationId(organizationId)
          .metricDate(today())
          .tasksCreated(99)
          .build());

      mockMvc.perform(get(base()).header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(jsonPath("$.data.totalTasksCreated").value(3));
    }

    @Test
    @DisplayName("an inverted or oversized range is refused")
    void rangeIsValidated() throws Exception {
      mockMvc.perform(get(base() + "?from=" + today() + "&to=" + today().minusDays(5))
              .header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isBadRequest());

      // A single query must not be able to pull years of rows.
      mockMvc.perform(get(base() + "?from=" + today().minusYears(5) + "&to=" + today())
              .header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isBadRequest());
    }
  }

  @Nested
  @DisplayName("authorization")
  class Authorization {

    @Test
    @DisplayName("a caller without project access gets 404, not metrics")
    void deniedProjectAccess() throws Exception {
      doThrow(new ResourceNotFoundException("Project not found"))
          .when(projectAccessClient)
          .requireProjectAccess(eq(organizationId), eq(projectId), any(), any());

      mockMvc.perform(get(base()).header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an unreachable project-service fails closed with 503")
    void unreachableAuthorityFailsClosed() throws Exception {
      doThrow(new ProjectAccessClient.ProjectServiceUnavailableException("unavailable", null))
          .when(projectAccessClient)
          .requireProjectAccess(any(), any(), any(), any());

      mockMvc.perform(get(base()).header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("no token, a refresh token and a wrongly signed token are all rejected")
    void tokensAreVerified() throws Exception {
      mockMvc.perform(get(base())).andExpect(status().isUnauthorized());
      mockMvc.perform(get(base())
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.refreshToken(user)))
          .andExpect(status().isUnauthorized());
      mockMvc.perform(get(base())
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.wronglySignedToken(user)))
          .andExpect(status().isUnauthorized());
    }
  }

  @Nested
  @DisplayName("the audit log")
  class Audit {

    private void audited(String type, UUID project, UUID organization, java.time.Instant when) {
      auditEntries.save(com.devforge.ai.analyticsservice.entity.AuditEntryEntity.builder()
          .id(UUID.randomUUID())
          .eventId(UUID.randomUUID())
          .eventType(type)
          .source("project-service")
          .organizationId(organization)
          .projectId(project)
          .actorId(user)
          .occurredAt(when)
          .details("{\"role\":\"VIEWER\"}")
          .build());
    }

    @Test
    @DisplayName("lists the project's entries newest first, with their details, to an admin")
    void adminReadsTheLog() throws Exception {
      var now = java.time.Instant.now();
      audited("ProjectMemberAdded", projectId, organizationId, now.minusSeconds(60));
      audited("ProjectMemberRemoved", projectId, organizationId, now);
      // Another project's entry, and the same project id under another tenant: neither is shown.
      audited("ProjectDeleted", UUID.randomUUID(), organizationId, now);
      audited("ProjectDeleted", projectId, UUID.randomUUID(), now);

      mockMvc.perform(get(base() + "/audit").header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content.length()").value(2))
          .andExpect(jsonPath("$.data.content[0].eventType").value("ProjectMemberRemoved"))
          .andExpect(jsonPath("$.data.content[1].eventType").value("ProjectMemberAdded"))
          .andExpect(jsonPath("$.data.content[0].details.role").value("VIEWER"));
    }

    @Test
    @DisplayName("is refused to a member who is not a project admin")
    void nonAdminIsRefused() throws Exception {
      doThrow(new org.springframework.security.access.AccessDeniedException("admin only"))
          .when(projectAccessClient).requireProjectAccess(eq(organizationId), eq(projectId), any(),
              eq(ProjectAccessClient.Access.ADMIN));

      mockMvc.perform(get(base() + "/audit").header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isForbidden());
      // The activity numbers stay readable to every member.
      mockMvc.perform(get(base()).header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isOk());
    }
  }

  @Nested
  @DisplayName("the audit chain")
  @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
  class AuditChainVerification {

    @Autowired private com.devforge.ai.analyticsservice.service.AuditChainAnchor anchor;

    @Test
    @DisplayName("the chain's head is written to the log stream, once per change, matching verification")
    void headIsAnchoredOutsideTheDatabase(org.springframework.boot.test.system.CapturedOutput output)
        throws Exception {
      record("ProjectCreated");
      record("ProjectUpdated");

      org.assertj.core.api.Assertions.assertThat(anchor.anchorChangedHeads()).isPositive();
      var head = new com.fasterxml.jackson.databind.ObjectMapper().readTree(verify().andReturn().getResponse().getContentAsString())
          .path("data").path("headHash").asText();
      var chain = com.devforge.ai.analyticsservice.entity.AuditChain.key(organizationId, projectId);
      org.assertj.core.api.Assertions.assertThat(output.getOut())
          .contains("audit-chain-anchor chain=" + chain + " sequence=2 head=" + head);

      // Unchanged since: nothing more is written for it.
      var before = output.getOut().split("chain=" + chain, -1).length;
      anchor.anchorChangedHeads();
      org.assertj.core.api.Assertions.assertThat(output.getOut().split("chain=" + chain, -1).length).isEqualTo(before);
    }

    /** Through the real recorder, in a transaction as the consumer runs it. */
    private void record(String type) {
      transactionTemplate.executeWithoutResult(status -> auditRecorder.record(
          com.devforge.ai.common.events.EventEnvelope.<java.util.Map<String, Object>>of(
              type, "project-service", organizationId, user, "corr-chain",
              java.util.Map.of("projectId", projectId.toString(), "name", type))));
    }

    private org.springframework.test.web.servlet.ResultActions verify() throws Exception {
      return mockMvc.perform(
          get(base() + "/audit/verification").header(HttpHeaders.AUTHORIZATION, bearer()));
    }

    private void tamper(String sql, long sequence) {
      // What someone with database access, but not the application, could do.
      jdbc.update(sql, com.devforge.ai.analyticsservice.entity.AuditChain.key(organizationId, projectId), sequence);
    }

    @Test
    @DisplayName("an untouched chain verifies, and reports the hash it ends on")
    void intactChainVerifies() throws Exception {
      record("ProjectCreated");
      record("ProjectMemberAdded");
      record("ProjectUpdated");

      verify()
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.intact").value(true))
          .andExpect(jsonPath("$.data.entries").value(3))
          .andExpect(jsonPath("$.data.unchainedEntries").value(0))
          .andExpect(jsonPath("$.data.headHash").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}")));
    }

    @Test
    @DisplayName("an edited entry is found, at its position")
    void editIsDetected() throws Exception {
      record("ProjectCreated");
      record("ProjectMemberAdded");
      record("ProjectUpdated");
      tamper("UPDATE audit_log SET details = '{\"role\":\"OWNER\"}' WHERE chain_key = ? AND chain_sequence = ?", 2);

      verify()
          .andExpect(jsonPath("$.data.intact").value(false))
          .andExpect(jsonPath("$.data.brokenAtSequence").value(2))
          .andExpect(jsonPath("$.data.problem").value("its content does not match its hash"))
          .andExpect(jsonPath("$.data.headHash").doesNotExist());
    }

    @Test
    @DisplayName("an entry deleted from the middle is found")
    void middleDeletionIsDetected() throws Exception {
      record("ProjectCreated");
      record("ProjectMemberRemoved");
      record("ProjectUpdated");
      tamper("DELETE FROM audit_log WHERE chain_key = ? AND chain_sequence = ?", 2);

      verify()
          .andExpect(jsonPath("$.data.intact").value(false))
          .andExpect(jsonPath("$.data.brokenAtSequence").value(2))
          .andExpect(jsonPath("$.data.problem").value("the entry is missing"));
    }

    @Test
    @DisplayName("entries deleted from the end are found, though what remains is consistent")
    void truncationIsDetected() throws Exception {
      record("ProjectCreated");
      record("ProjectMemberRemoved");
      tamper("DELETE FROM audit_log WHERE chain_key = ? AND chain_sequence = ?", 2);

      verify()
          .andExpect(jsonPath("$.data.intact").value(false))
          .andExpect(jsonPath("$.data.entries").value(1))
          .andExpect(jsonPath("$.data.brokenAtSequence").value(2))
          .andExpect(jsonPath("$.data.problem").value("the chain stops short of the 2 entries it recorded"));
    }

    @Test
    @DisplayName("each project is its own chain, so another project's entries neither break nor join it")
    void chainsAreIndependent() throws Exception {
      record("ProjectCreated");
      // Another project in the same organization, through the same recorder.
      transactionTemplate.executeWithoutResult(status -> auditRecorder.record(
          com.devforge.ai.common.events.EventEnvelope.<java.util.Map<String, Object>>of(
              "ProjectCreated", "project-service", organizationId, user, "corr-other",
              java.util.Map.of("projectId", UUID.randomUUID().toString()))));
      record("ProjectUpdated");

      verify()
          .andExpect(jsonPath("$.data.intact").value(true))
          .andExpect(jsonPath("$.data.entries").value(2));
    }

    @Test
    @DisplayName("is refused to a member who is not a project admin")
    void nonAdminIsRefused() throws Exception {
      doThrow(new org.springframework.security.access.AccessDeniedException("admin only"))
          .when(projectAccessClient).requireProjectAccess(eq(organizationId), eq(projectId), any(),
              eq(ProjectAccessClient.Access.ADMIN));

      verify().andExpect(status().isForbidden());
    }
  }
}
