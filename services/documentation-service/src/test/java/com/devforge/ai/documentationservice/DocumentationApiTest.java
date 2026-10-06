package com.devforge.ai.documentationservice;

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
import com.devforge.ai.common.git.GitContentClient;
import com.devforge.ai.common.git.RepositoryFile;
import com.devforge.ai.common.security.client.ProjectAccessClient;
import com.devforge.ai.documentationservice.model.DocSetStatus;
import com.devforge.ai.documentationservice.repository.DocSetRepository;
import com.devforge.ai.documentationservice.repository.DocumentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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
 * Documentation generation end to end: real generators, real persistence, real filter chain.
 *
 * <p>Only the two outbound clients are mocked, so the tests supply file content directly and assert
 * on what was generated from it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Documentation API")
class DocumentationApiTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private DocSetRepository docSets;
  @Autowired private DocumentRepository documents;

  @MockitoBean private ProjectAccessClient projectAccessClient;
  @MockitoBean private GitContentClient gitContentClient;

  private final UUID organizationId = UUID.randomUUID();
  private final UUID projectId = UUID.randomUUID();
  private final UUID repositoryId = UUID.randomUUID();
  private final UUID user = UUID.randomUUID();

  private String base() {
    return "/api/v1/organizations/" + organizationId + "/projects/" + projectId
        + "/repositories/" + repositoryId + "/docs";
  }

  private String bearer() {
    return "Bearer " + TestTokens.accessToken(user);
  }

  @BeforeEach
  void setUp() {
    documents.deleteAll();
    docSets.deleteAll();
    when(gitContentClient.defaultBranch(any(), any())).thenReturn("main");
  }

  private void repositoryContains(RepositoryFile... files) {
    when(gitContentClient.filesToAnalyse(any(), any(), any(), any())).thenReturn(List.of(files));
  }

  private static RepositoryFile file(String path, String content) {
    return RepositoryFile.of(path, content.length(), false, false, content);
  }

  private UUID generate() throws Exception {
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
  @DisplayName("generating")
  class Generating {

    @Test
    @DisplayName("produces documents from the repository's own content")
    void generatesDocuments() throws Exception {
      repositoryContains(
          file("pom.xml", "<project/>"),
          file("README.md", "# Payments\n\n## Getting started\n"),
          file("src/UserController.java", """
              @RestController
              @RequestMapping("/api/v1/users")
              public class UserController {
                /** Lists users. */
                @GetMapping
                public List<User> list() { return List.of(); }
              }
              """));

      mockMvc.perform(post(base())
              .header(HttpHeaders.AUTHORIZATION, bearer())
              .contentType(MediaType.APPLICATION_JSON)
              .content("{}"))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.data.status").value("COMPLETED"))
          .andExpect(jsonPath("$.data.ref").value("main"))
          .andExpect(jsonPath("$.data.filesScanned").value(3));

      assertThat(documents.findAll()).hasSize(3);
    }

    @Test
    @DisplayName("the API surface document names the endpoint that is actually there")
    void apiSurfaceReflectsTheSource() throws Exception {
      repositoryContains(file("src/UserController.java", """
          @RestController
          @RequestMapping("/api/v1/users")
          public class UserController {
            @GetMapping("/{id}")
            public User get(@PathVariable UUID id) { return null; }
          }
          """));

      var setId = generate();

      mockMvc.perform(get(base() + "/" + setId + "/documents/API_SURFACE")
              .header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.kind").value("API_SURFACE"))
          .andExpect(jsonPath("$.data.content").value(
              org.hamcrest.Matchers.containsString("/api/v1/users/{id}")))
          // The document must state its own limits, so an absence is never read as proof.
          .andExpect(jsonPath("$.data.content").value(
              org.hamcrest.Matchers.containsString("Limits of this scan")));
    }

    @Test
    @DisplayName("omits a document rather than producing an empty one")
    void omitsEmptyDocuments() throws Exception {
      // A README alone yields an overview but no API surface and no measurable public surface.
      repositoryContains(file("README.md", "# Just a readme\n"));

      var setId = generate();

      var kinds = documents.findByDocSetIdOrderByKindAsc(setId).stream()
          .map(d -> d.getKind().name())
          .toList();
      // A document saying nothing is worse than its absence: a reader cannot tell it from a
      // document whose generator failed.
      assertThat(kinds).containsExactly("OVERVIEW");
    }

    @Test
    @DisplayName("the listing omits content, which is fetched per document")
    void listingOmitsContent() throws Exception {
      repositoryContains(file("src/App.java", "public class App {}"));
      var setId = generate();

      mockMvc.perform(get(base() + "/" + setId + "/documents")
              .header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data[0].title").isNotEmpty())
          .andExpect(jsonPath("$.data[0].content").doesNotExist());
    }

    @Test
    @DisplayName("the newest set is served as the latest")
    void latestReturnsTheNewest() throws Exception {
      repositoryContains(file("src/App.java", "public class App {}"));
      generate();
      var second = generate();

      mockMvc.perform(get(base() + "/latest").header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.id").value(second.toString()));
    }

    @Test
    @DisplayName("a repository with no documentation is 404, not an empty set")
    void latestIsNotFoundWhenNeverGenerated() throws Exception {
      mockMvc.perform(get(base() + "/latest").header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a kind that was not produced is 404")
    void missingKindIsNotFound() throws Exception {
      repositoryContains(file("README.md", "# Just a readme\n"));
      var setId = generate();

      mockMvc.perform(get(base() + "/" + setId + "/documents/API_SURFACE")
              .header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isNotFound());
    }
  }

  @Nested
  @DisplayName("when the code cannot be read")
  class FailsClosed {

    @Test
    @DisplayName("is 503, and the set is recorded as FAILED rather than empty")
    void unreadableContentFailsClosed() throws Exception {
      when(gitContentClient.filesToAnalyse(any(), any(), any(), any()))
          .thenThrow(new GitContentClient.GitServiceUnavailableException(
              "Cannot read the repository right now. Please try again.", null));

      mockMvc.perform(post(base())
              .header(HttpHeaders.AUTHORIZATION, bearer())
              .contentType(MediaType.APPLICATION_JSON)
              .content("{}"))
          .andExpect(status().isServiceUnavailable());

      // Documentation generated without reading the code would describe a repository with no API
      // and no public surface — confident and wrong, which the reader cannot detect.
      var stored = docSets.findAll();
      assertThat(stored).hasSize(1);
      assertThat(stored.get(0).getStatus()).isEqualTo(DocSetStatus.FAILED);
      assertThat(stored.get(0).getFailureReason()).contains("Cannot read the repository");
      assertThat(documents.count()).isZero();
    }
  }

  @Nested
  @DisplayName("authorization")
  class Authorization {

    @Test
    @DisplayName("a set in another project is 404, not 403")
    void otherProjectIsNotFound() throws Exception {
      repositoryContains(file("src/App.java", "public class App {}"));
      var setId = generate();

      var otherBase = "/api/v1/organizations/" + organizationId + "/projects/" + UUID.randomUUID()
          + "/repositories/" + repositoryId + "/docs/" + setId + "/documents";

      mockMvc.perform(get(otherBase).header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isNotFound());
      assertThat(docSets.findById(setId)).isPresent();
    }

    @Test
    @DisplayName("a caller without project access cannot generate or read")
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

      assertThat(docSets.count()).isZero();
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
    @DisplayName("no token, a refresh token, and a wrongly signed token are all rejected")
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
