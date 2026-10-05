package com.devforge.ai.reviewservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devforge.ai.common.exception.ResourceNotFoundException;
import com.devforge.ai.common.security.client.ProjectAccessClient;
import com.devforge.ai.reviewservice.analysis.AnalysedFile;
import com.devforge.ai.reviewservice.client.GitContentClient;
import com.devforge.ai.reviewservice.model.ReviewModel.ReviewStatus;
import com.devforge.ai.reviewservice.repository.FindingRepository;
import com.devforge.ai.reviewservice.repository.ReviewRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The review API end to end: real rules, real persistence, real filter chain.
 *
 * <p>Only the two outbound clients are mocked. {@link GitContentClient} stands in for git-service so
 * the tests supply file content directly — that is what makes it possible to assert on a repository
 * containing a credential without committing one to this repository.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Review API")
class ReviewApiTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private ReviewRepository reviews;
  @Autowired private FindingRepository findings;

  @MockitoBean private ProjectAccessClient projectAccessClient;
  @MockitoBean private GitContentClient gitContentClient;

  private final UUID organizationId = UUID.randomUUID();
  private final UUID projectId = UUID.randomUUID();
  private final UUID repositoryId = UUID.randomUUID();
  private final UUID user = UUID.randomUUID();

  private String base() {
    return "/api/v1/organizations/" + organizationId + "/projects/" + projectId
        + "/repositories/" + repositoryId + "/reviews";
  }

  private String bearer() {
    return "Bearer " + TestTokens.accessToken(user);
  }

  @BeforeEach
  void setUp() {
    findings.deleteAll();
    reviews.deleteAll();
    when(gitContentClient.defaultBranch(any(), any())).thenReturn("main");
  }

  private void repositoryContains(AnalysedFile... files) {
    when(gitContentClient.filesToAnalyse(any(), any(), any(), any())).thenReturn(List.of(files));
  }

  private static AnalysedFile file(String path, String content) {
    return AnalysedFile.of(path, content.length(), false, false, content);
  }

  /** Runs a review and returns its id. */
  private UUID runReview() throws Exception {
    var response = mockMvc.perform(post(base())
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isCreated())
        .andReturn()
        .getResponse()
        .getContentAsString();
    return UUID.fromString(objectMapper.readTree(response).path("data").path("id").asText());
  }

  @Nested
  @DisplayName("running a review")
  class Running {

    @Test
    @DisplayName("a clean repository passes the gate")
    void cleanRepositoryPasses() throws Exception {
      repositoryContains(file("src/App.java", "class App { void run() {} }"));

      mockMvc.perform(post(base())
              .header(HttpHeaders.AUTHORIZATION, bearer())
              .contentType(MediaType.APPLICATION_JSON)
              .content("{}"))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.data.status").value("COMPLETED"))
          .andExpect(jsonPath("$.data.gate").value("PASS"))
          .andExpect(jsonPath("$.data.ref").value("main"))
          .andExpect(jsonPath("$.data.filesAnalysed").value(1))
          .andExpect(jsonPath("$.data.blockerCount").value(0));
    }

    @Test
    @DisplayName("a committed credential fails the gate and is recorded as a finding")
    void credentialFailsTheGate() throws Exception {
      repositoryContains(file("src/Config.java", "String key = \"AKIAIOSFODNN7EXAMPLX\";"));

      var reviewId = runReview();

      mockMvc.perform(get(base() + "/" + reviewId).header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(jsonPath("$.data.gate").value("FAIL"))
          .andExpect(jsonPath("$.data.blockerCount").value(1));

      mockMvc.perform(get(base() + "/" + reviewId + "/findings")
              .header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.length()").value(1))
          .andExpect(jsonPath("$.data[0].severity").value("BLOCKER"))
          .andExpect(jsonPath("$.data[0].category").value("SECRET"))
          .andExpect(jsonPath("$.data[0].filePath").value("src/Config.java"))
          .andExpect(jsonPath("$.data[0].lineNumber").value(1))
          .andExpect(jsonPath("$.data[0].dismissed").value(false));
    }

    @Test
    @DisplayName("the stored finding does not contain the credential")
    void storedFindingIsRedacted() throws Exception {
      repositoryContains(file("src/Config.java", "String key = \"AKIAIOSFODNN7EXAMPLX\";"));
      runReview();

      var stored = findings.findAll();
      assertThat(stored).hasSize(1);
      // The point of redaction: the database is not a second place the secret lives.
      assertThat(stored.get(0).getSnippet()).doesNotContain("AKIAIOSFODNN7EXAMPLX");
      assertThat(stored.get(0).getSnippet()).contains("[REDACTED");
    }

    @Test
    @DisplayName("findings come back worst-first")
    void findingsAreOrderedBySeverity() throws Exception {
      repositoryContains(
          file("src/app.js", "const r = eval(x);"),
          file("src/Config.java", "String key = \"AKIAIOSFODNN7EXAMPLX\";"),
          file("src/Merge.java", "<<<<<<< HEAD"));

      var reviewId = runReview();

      mockMvc.perform(get(base() + "/" + reviewId + "/findings")
              .header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(jsonPath("$.data[0].severity").value("BLOCKER"))
          .andExpect(jsonPath("$.data[1].severity").value("HIGH"))
          .andExpect(jsonPath("$.data[2].severity").value("MEDIUM"));
    }

    @Test
    @DisplayName("a diff review passes both refs through and records them")
    void diffReviewRecordsBothRefs() throws Exception {
      repositoryContains(file("src/App.java", "class App {}"));

      var body = objectMapper.writeValueAsString(Map.of("ref", "feature/x", "baseRef", "main"));
      mockMvc.perform(post(base())
              .header(HttpHeaders.AUTHORIZATION, bearer())
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.data.ref").value("feature/x"))
          .andExpect(jsonPath("$.data.baseRef").value("main"));

      // The ref was taken from the request, so git-service's default was never consulted.
      org.mockito.Mockito.verify(gitContentClient, org.mockito.Mockito.never())
          .defaultBranch(any(), any());
    }

    @Test
    @DisplayName("comparing a ref with itself is refused")
    void sameRefIsRejected() throws Exception {
      var body = objectMapper.writeValueAsString(Map.of("ref", "main", "baseRef", "main"));

      mockMvc.perform(post(base())
              .header(HttpHeaders.AUTHORIZATION, bearer())
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isConflict());

      assertThat(reviews.count()).isZero();
    }

    @Test
    @DisplayName("the newest review is served as the latest")
    void latestReturnsTheNewest() throws Exception {
      repositoryContains(file("src/App.java", "class App {}"));
      runReview();

      repositoryContains(file("src/Config.java", "String key = \"AKIAIOSFODNN7EXAMPLX\";"));
      var second = runReview();

      mockMvc.perform(get(base() + "/latest").header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.id").value(second.toString()))
          .andExpect(jsonPath("$.data.gate").value("FAIL"));
    }

    @Test
    @DisplayName("a repository that has never been reviewed is 404, not an empty pass")
    void latestIsNotFoundWhenNeverReviewed() throws Exception {
      mockMvc.perform(get(base() + "/latest").header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isNotFound());
    }
  }

  @Nested
  @DisplayName("when the code cannot be read")
  class FailsClosed {

    @Test
    @DisplayName("an unreachable git-service is 503, and the review is recorded as FAILED")
    void unreadableContentFailsClosed() throws Exception {
      when(gitContentClient.filesToAnalyse(any(), any(), any(), any()))
          .thenThrow(new GitContentClient.GitServiceUnavailableException(
              "Cannot read the repository right now. Please try again.", null));

      mockMvc.perform(post(base())
              .header(HttpHeaders.AUTHORIZATION, bearer())
              .contentType(MediaType.APPLICATION_JSON)
              .content("{}"))
          .andExpect(status().isServiceUnavailable());

      // The crucial part. "No problems found" when the code could not be read is
      // indistinguishable from a clean repository, and would be trusted as a pass.
      var stored = reviews.findAll();
      assertThat(stored).hasSize(1);
      assertThat(stored.get(0).getStatus()).isEqualTo(ReviewStatus.FAILED);
      assertThat(stored.get(0).getGate()).isNull();
      assertThat(stored.get(0).getFailureReason()).contains("Cannot read the repository");
      assertThat(stored.get(0).getCompletedAt()).isNotNull();
    }
  }

  @Nested
  @DisplayName("dismissing a finding")
  class Dismissing {

    @Test
    @DisplayName("records who dismissed it and why, without changing the gate")
    void dismissRecordsReasonAndLeavesTheGate() throws Exception {
      repositoryContains(file("src/Config.java", "String key = \"AKIAIOSFODNN7EXAMPLX\";"));
      var reviewId = runReview();
      var findingId = findings.findAll().get(0).getId();

      var body = objectMapper.writeValueAsString(Map.of("reason", "Rotated; this is the old key"));
      mockMvc.perform(post(base() + "/" + reviewId + "/findings/" + findingId + "/dismiss")
              .header(HttpHeaders.AUTHORIZATION, bearer())
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.dismissed").value(true))
          .andExpect(jsonPath("$.data.dismissReason").value("Rotated; this is the old key"))
          .andExpect(jsonPath("$.data.dismissedBy").value(user.toString()));

      // The gate records what the analysis found at the time. Letting a dismissal rewrite it would
      // make the history useless and turn the gate into something anyone can clear.
      mockMvc.perform(get(base() + "/" + reviewId).header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(jsonPath("$.data.gate").value("FAIL"))
          .andExpect(jsonPath("$.data.blockerCount").value(1));
    }

    @Test
    @DisplayName("a dismissal without a reason is rejected")
    void reasonIsRequired() throws Exception {
      repositoryContains(file("src/Config.java", "String key = \"AKIAIOSFODNN7EXAMPLX\";"));
      var reviewId = runReview();
      var findingId = findings.findAll().get(0).getId();

      for (var body : List.of("{}", "{\"reason\":\"\"}", "{\"reason\":\"   \"}")) {
        mockMvc.perform(post(base() + "/" + reviewId + "/findings/" + findingId + "/dismiss")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
      }

      // A dismissal with no reason is indistinguishable from someone clearing the list.
      assertThat(findings.findById(findingId).orElseThrow().isDismissed()).isFalse();
    }

    @Test
    @DisplayName("dismissing twice is refused")
    void doubleDismissIsConflict() throws Exception {
      repositoryContains(file("src/Config.java", "String key = \"AKIAIOSFODNN7EXAMPLX\";"));
      var reviewId = runReview();
      var findingId = findings.findAll().get(0).getId();
      var body = objectMapper.writeValueAsString(Map.of("reason", "known"));

      mockMvc.perform(post(base() + "/" + reviewId + "/findings/" + findingId + "/dismiss")
              .header(HttpHeaders.AUTHORIZATION, bearer())
              .contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isOk());
      mockMvc.perform(post(base() + "/" + reviewId + "/findings/" + findingId + "/dismiss")
              .header(HttpHeaders.AUTHORIZATION, bearer())
              .contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isConflict());
    }
  }

  @Nested
  @DisplayName("authorization")
  class Authorization {

    @Test
    @DisplayName("a review in another project is 404, not 403")
    void otherProjectIsNotFound() throws Exception {
      repositoryContains(file("src/App.java", "class App {}"));
      var reviewId = runReview();

      var otherProject = UUID.randomUUID();
      var otherBase = "/api/v1/organizations/" + organizationId + "/projects/" + otherProject
          + "/repositories/" + repositoryId + "/reviews/" + reviewId;

      // 403 would confirm the id exists, making this an oracle for enumerating reviews.
      mockMvc.perform(get(otherBase).header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isNotFound());
      mockMvc.perform(get(otherBase + "/findings").header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isNotFound());

      assertThat(reviews.findById(reviewId)).isPresent();
    }

    @Test
    @DisplayName("a caller without project access cannot run or read reviews")
    void deniedProjectAccess() throws Exception {
      doThrow(new ResourceNotFoundException("Project not found"))
          .when(projectAccessClient)
          .requireProjectAccess(eq(organizationId), eq(projectId), any());

      mockMvc.perform(get(base()).header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isNotFound());
      mockMvc.perform(post(base())
              .header(HttpHeaders.AUTHORIZATION, bearer())
              .contentType(MediaType.APPLICATION_JSON).content("{}"))
          .andExpect(status().isNotFound());

      assertThat(reviews.count()).isZero();
    }

    @Test
    @DisplayName("an unreachable project-service fails closed with 503")
    void unreachableAuthorityFailsClosed() throws Exception {
      doThrow(new ProjectAccessClient.ProjectServiceUnavailableException(
              "Cannot verify project access right now. Please try again.",
              new RuntimeException("connection refused")))
          .when(projectAccessClient)
          .requireProjectAccess(any(), any(), any());

      mockMvc.perform(get(base()).header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("no token is rejected")
    void anonymousIsRejected() throws Exception {
      mockMvc.perform(get(base())).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a refresh token cannot be used as an access token")
    void refreshTokenIsRejected() throws Exception {
      mockMvc.perform(get(base())
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.refreshToken(user)))
          .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a token signed with another key is rejected")
    void wronglySignedTokenIsRejected() throws Exception {
      mockMvc.perform(get(base())
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.wronglySignedToken(user)))
          .andExpect(status().isUnauthorized());
    }
  }
}
