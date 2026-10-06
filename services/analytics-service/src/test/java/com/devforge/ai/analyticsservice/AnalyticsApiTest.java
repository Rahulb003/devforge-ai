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
          .requireProjectAccess(eq(organizationId), eq(projectId), any());

      mockMvc.perform(get(base()).header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an unreachable project-service fails closed with 503")
    void unreachableAuthorityFailsClosed() throws Exception {
      doThrow(new ProjectAccessClient.ProjectServiceUnavailableException("unavailable", null))
          .when(projectAccessClient)
          .requireProjectAccess(any(), any(), any());

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
}
