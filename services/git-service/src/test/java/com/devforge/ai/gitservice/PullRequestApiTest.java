package com.devforge.ai.gitservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devforge.ai.common.events.EventTypes;
import com.devforge.ai.common.events.outbox.OutboxEventRepository;
import com.devforge.ai.common.exception.ResourceNotFoundException;
import com.devforge.ai.common.security.client.ProjectAccessClient;
import com.devforge.ai.gitservice.repository.GitRepositoryRepository;
import com.devforge.ai.gitservice.repository.PullRequestRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Pull requests end to end, on real JGit repositories: branches are created and committed to,
 * and merges are real three-way merges. Only project access is stubbed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Pull request API")
class PullRequestApiTest {

  static final java.nio.file.Path STORAGE_ROOT;

  static {
    try {
      STORAGE_ROOT = java.nio.file.Files.createTempDirectory("devforge-pr-test");
      STORAGE_ROOT.toFile().deleteOnExit();
    } catch (java.io.IOException ex) {
      throw new IllegalStateException(ex);
    }
  }

  @DynamicPropertySource
  static void storage(DynamicPropertyRegistry registry) {
    registry.add("devforge.git.storage-root", STORAGE_ROOT::toString);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private GitRepositoryRepository repositories;
  @Autowired private PullRequestRepository pullRequests;
  @Autowired private OutboxEventRepository outboxEvents;
  @MockitoBean private ProjectAccessClient projectAccessClient;

  private final UUID organizationId = UUID.randomUUID();
  private final UUID projectId = UUID.randomUUID();
  private final UUID user = UUID.randomUUID();
  private UUID repo;

  private String base() {
    return "/api/v1/organizations/" + organizationId + "/projects/" + projectId + "/repositories";
  }

  private String prs() {
    return base() + "/" + repo + "/pull-requests";
  }

  private String bearer() {
    return "Bearer " + TestTokens.accessToken(user);
  }

  private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
      Object body) throws Exception {
    request.header(HttpHeaders.AUTHORIZATION, bearer());
    if (body != null) {
      request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
    }
    return mockMvc.perform(request);
  }

  private JsonNode data(ResultActions result) throws Exception {
    return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
  }

  private String commit(String branch, String path, String content) throws Exception {
    var body = new java.util.HashMap<String, Object>(
        Map.of("path", path, "content", content, "message", "Change " + path));
    if (branch != null) {
      body.put("branch", branch);
    }
    return data(send(post(base() + "/" + repo + "/files"), body).andExpect(status().isCreated()))
        .path("id").asText();
  }

  private void branch(String name) throws Exception {
    send(post(base() + "/" + repo + "/branches"), Map.of("name", name)).andExpect(status().isCreated());
  }

  private ResultActions open(String source, String title) throws Exception {
    return send(post(prs()), Map.of("title", title, "sourceBranch", source));
  }

  private String file(String ref, String path) throws Exception {
    return data(send(get(base() + "/" + repo + "/blob").param("path", path).param("ref", ref), null)
        .andExpect(status().isOk())).path("content").asText();
  }

  @BeforeEach
  void setUp() throws Exception {
    pullRequests.deleteAll();
    repositories.deleteAll();
    outboxEvents.deleteAll();
    repo = UUID.fromString(data(send(post(base()), Map.of("name", "api-" + UUID.randomUUID().toString().substring(0, 8)))
        .andExpect(status().isCreated())).path("id").asText());
    commit(null, "README.md", "readme\n");
    branch("feature");
  }

  @Nested
  @DisplayName("opening")
  class Opening {

    @Test
    @DisplayName("opens against the default branch, numbered per repository, and reports it mergeable")
    void opensMergeable() throws Exception {
      commit("feature", "src/app.ts", "export {};\n");

      var opened = data(open("feature", "Add app").andExpect(status().isCreated()));
      assertThat(opened.path("number").asInt()).isEqualTo(1);
      assertThat(opened.path("targetBranch").asText()).isEqualTo("main");
      assertThat(opened.path("status").asText()).isEqualTo("OPEN");
      assertThat(opened.path("conflicts").size()).isZero();

      branch("feature-2");
      commit("feature-2", "b.txt", "b\n");
      assertThat(data(open("feature-2", "Second").andExpect(status().isCreated())).path("number").asInt())
          .isEqualTo(2);

      send(get(prs()), null).andExpect(jsonPath("$.data.length()").value(2))
          .andExpect(jsonPath("$.data[0].number").value(2));
      send(get(prs()).param("status", "merged"), null).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("refuses a duplicate, a branch into itself, a missing branch, and nothing to merge")
    void refusesInvalidPullRequests() throws Exception {
      // "feature" has no commits of its own yet: main already contains it.
      open("feature", "Empty").andExpect(status().isBadRequest());

      commit("feature", "a.txt", "a\n");
      open("feature", "First").andExpect(status().isCreated());
      open("feature", "Again").andExpect(status().isConflict());

      send(post(prs()), Map.of("title", "Self", "sourceBranch", "main")).andExpect(status().isBadRequest());
      open("no-such-branch", "Ghost").andExpect(status().isNotFound());
      send(post(prs()), Map.of("title", "", "sourceBranch", "feature")).andExpect(status().isBadRequest());
    }
  }

  @Nested
  @DisplayName("merging")
  class Merging {

    @Test
    @DisplayName("merges with a two-parent commit, and the diff shows only what the source added")
    void mergesCleanly() throws Exception {
      commit("feature", "src/app.ts", "export {};\n");
      // The target moves on independently; that change is not part of the pull request.
      commit("main", "CHANGELOG.md", "unrelated\n");
      open("feature", "Add app").andExpect(status().isCreated());

      send(get(prs() + "/1/diff"), null)
          .andExpect(jsonPath("$.data.entries.length()").value(1))
          .andExpect(jsonPath("$.data.entries[0].newPath").value("src/app.ts"));

      var merged = data(send(post(prs() + "/1/merge"), null).andExpect(status().isOk()));
      assertThat(merged.path("status").asText()).isEqualTo("MERGED");
      var mergeCommit = merged.path("mergeCommitId").asText();

      send(get(base() + "/" + repo + "/commits").param("ref", "main"), null)
          .andExpect(jsonPath("$.data[0].id").value(mergeCommit))
          .andExpect(jsonPath("$.data[0].parentIds.length()").value(2));
      assertThat(file("main", "src/app.ts")).isEqualTo("export {};\n");
      assertThat(file("main", "CHANGELOG.md")).isEqualTo("unrelated\n");

      // Still meaningful after merging: what the merge brought in.
      send(get(prs() + "/1/diff"), null)
          .andExpect(jsonPath("$.data.entries.length()").value(1))
          .andExpect(jsonPath("$.data.entries[0].newPath").value("src/app.ts"));
      send(post(prs() + "/1/merge"), null).andExpect(status().isConflict());

      assertThat(outboxEvents.findAll())
          .anySatisfy(row -> assertThat(row.getEventType()).isEqualTo(EventTypes.PULL_REQUEST_MERGED));
    }

    @Test
    @DisplayName("reports conflicts, refuses to merge them, and leaves the target untouched")
    void conflictsAreReportedNotMerged() throws Exception {
      commit("feature", "README.md", "feature version\n");
      var mainHead = commit("main", "README.md", "main version\n");
      open("feature", "Rewrite readme").andExpect(status().isCreated());

      send(get(prs() + "/1"), null)
          .andExpect(jsonPath("$.data.conflicts[0]").value("README.md"));
      send(post(prs() + "/1/merge"), null)
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.message").value("Merge conflicts in: README.md"));

      send(get(prs() + "/1"), null).andExpect(jsonPath("$.data.status").value("OPEN"));
      send(get(base() + "/" + repo + "/commits").param("ref", "main"), null)
          .andExpect(jsonPath("$.data[0].id").value(mainHead));
    }

    @Test
    @DisplayName("refuses to merge a source that moved since it was reviewed")
    void staleReviewIsRefused() throws Exception {
      var reviewed = commit("feature", "a.txt", "reviewed\n");
      open("feature", "Change a").andExpect(status().isCreated());
      commit("feature", "a.txt", "sneaked in after review\n");

      send(post(prs() + "/1/merge"), Map.of("expectedSourceHead", reviewed))
          .andExpect(status().isConflict());
      send(get(prs() + "/1"), null).andExpect(jsonPath("$.data.status").value("OPEN"));

      // With the head the reviewer actually saw, it merges.
      var current = data(send(get(prs() + "/1"), null)).path("sourceHead").asText();
      send(post(prs() + "/1/merge"), Map.of("expectedSourceHead", current)).andExpect(status().isOk());
      assertThat(file("main", "a.txt")).isEqualTo("sneaked in after review\n");
    }

    @Test
    @DisplayName("a closed pull request cannot be merged, and can be opened again as a new one")
    void closedCannotMerge() throws Exception {
      commit("feature", "a.txt", "a\n");
      open("feature", "Change a").andExpect(status().isCreated());

      send(post(prs() + "/1/close"), null).andExpect(jsonPath("$.data.status").value("CLOSED"));
      send(post(prs() + "/1/merge"), null).andExpect(status().isConflict());
      send(post(prs() + "/1/close"), null).andExpect(status().isConflict());
      // Closing the old one frees the branch pair for a fresh pull request.
      assertThat(data(open("feature", "Try again").andExpect(status().isCreated())).path("number").asInt())
          .isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("access")
  class Access {

    @Test
    @DisplayName("a caller without project access gets 404 and cannot merge")
    void deniedAccessIsNotFound() throws Exception {
      commit("feature", "a.txt", "a\n");
      open("feature", "Change a").andExpect(status().isCreated());
      doThrow(new ResourceNotFoundException("Project not found"))
          .when(projectAccessClient).requireProjectAccess(eq(organizationId), eq(projectId), any(), any());

      send(get(prs()), null).andExpect(status().isNotFound());
      send(post(prs() + "/1/merge"), null).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an unknown number is 404, and deleting the repository removes its pull requests")
    void unknownNumberAndCascade() throws Exception {
      commit("feature", "a.txt", "a\n");
      open("feature", "Change a").andExpect(status().isCreated());
      send(get(prs() + "/99"), null).andExpect(status().isNotFound());

      send(delete(base() + "/" + repo), null).andExpect(status().is2xxSuccessful());
      assertThat(pullRequests.findAll()).isEmpty();
    }
  }
}
