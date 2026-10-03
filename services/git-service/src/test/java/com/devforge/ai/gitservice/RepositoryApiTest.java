package com.devforge.ai.gitservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devforge.ai.common.events.EventTypes;
import com.devforge.ai.common.events.outbox.OutboxEventRepository;
import com.devforge.ai.common.exception.ResourceNotFoundException;
import com.devforge.ai.common.security.client.ProjectAccessClient;
import com.devforge.ai.gitservice.repository.GitRepositoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Repositories end to end: real JGit repositories on a temporary directory.
 *
 * <p>Nothing about git is mocked. The tests create repositories, commit to them, branch, read trees
 * and blobs and diff — so a wrong assumption about JGit's API surfaces here rather than at runtime.
 * Only {@link ProjectAccessClient} is replaced, because the network between two services is not what
 * these tests are about.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Repository API")
class RepositoryApiTest {

  /**
   * Git objects go to a per-run temporary directory.
   *
   * <p>Static and created once, because {@code @DynamicPropertySource} is evaluated before any
   * instance exists. A fixed path under {@code target/} would accumulate repositories across runs and
   * let one run's leftovers change another's results.
   */
  static final java.nio.file.Path STORAGE_ROOT;

  static {
    try {
      STORAGE_ROOT = java.nio.file.Files.createTempDirectory("devforge-git-test");
      STORAGE_ROOT.toFile().deleteOnExit();
    } catch (java.io.IOException ex) {
      throw new IllegalStateException("Could not create a temporary storage root", ex);
    }
  }

  @DynamicPropertySource
  static void storage(DynamicPropertyRegistry registry) {
    registry.add("devforge.git.storage-root", STORAGE_ROOT::toString);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private GitRepositoryRepository repositories;
  @Autowired private OutboxEventRepository outboxEvents;

  /** Authorization is project-service's decision; here it is stubbed to "allowed" by default. */
  @MockitoBean private ProjectAccessClient projectAccessClient;

  private final UUID organizationId = UUID.randomUUID();
  private final UUID projectId = UUID.randomUUID();
  private final UUID user = UUID.randomUUID();

  private String base() {
    return "/api/v1/organizations/" + organizationId + "/projects/" + projectId + "/repositories";
  }

  private String bearer() {
    return "Bearer " + TestTokens.accessToken(user);
  }

  @BeforeEach
  void setUp() {
    repositories.deleteAll();
    outboxEvents.deleteAll();
  }

  /** Creates a repository and returns its id. */
  private UUID createRepository(String name) throws Exception {
    var body = objectMapper.writeValueAsString(
        java.util.Map.of("name", name, "description", "test repo"));
    var response = mockMvc.perform(post(base())
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andReturn()
        .getResponse()
        .getContentAsString();
    return UUID.fromString(objectMapper.readTree(response).path("data").path("id").asText());
  }

  private String commit(UUID repositoryId, String path, String content, String message)
      throws Exception {
    var body = objectMapper.writeValueAsString(
        java.util.Map.of("path", path, "content", content, "message", message));
    var response = mockMvc.perform(post(base() + "/" + repositoryId + "/files")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andReturn()
        .getResponse()
        .getContentAsString();
    return objectMapper.readTree(response).path("data").path("id").asText();
  }

  @Nested
  @DisplayName("creating")
  class Creating {

    @Test
    @DisplayName("creates a real bare repository on disk, reported as empty")
    void createsRealRepository() throws Exception {
      var id = createRepository("payments-api");

      var entity = repositories.findById(id).orElseThrow();
      assertThat(entity.getDefaultBranch()).isEqualTo("main");

      // The path is derived from ids, never from the name.
      var directory = STORAGE_ROOT.resolve(organizationId.toString()).resolve(id + ".git");
      assertThat(directory).exists();
      assertThat(directory.resolve("HEAD")).exists();
      assertThat(directory.resolve("objects")).exists();

      mockMvc.perform(get(base() + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.empty").value(true))
          .andExpect(jsonPath("$.data.name").value("payments-api"));
    }

    @Test
    @DisplayName("the on-disk path contains no part of the repository name")
    void storagePathIsDerivedFromIdsOnly() throws Exception {
      var id = createRepository("payments-api");

      try (var walk = java.nio.file.Files.walk(STORAGE_ROOT, 3)) {
        var paths = walk.map(java.nio.file.Path::toString).toList();
        // A name-derived path would make repository creation a filesystem write addressed by user
        // input. The id is already unique, so the name buys nothing and costs a traversal surface.
        assertThat(paths).noneMatch(p -> p.contains("payments-api"));
        assertThat(paths).anyMatch(p -> p.contains(id.toString()));
      }
    }

    @Test
    @DisplayName("stages a RepositoryCreated event in the same transaction")
    void stagesCreationEvent() throws Exception {
      var id = createRepository("payments-api");

      assertThat(outboxEvents.findAll())
          .anySatisfy(row -> {
            assertThat(row.getEventType()).isEqualTo(EventTypes.REPOSITORY_CREATED);
            assertThat(row.getPayload()).contains(id.toString());
            assertThat(row.getPartitionKey()).isEqualTo(organizationId.toString());
          });
    }

    @Test
    @DisplayName("rejects a duplicate name within the project with 409")
    void rejectsDuplicateName() throws Exception {
      createRepository("api");

      var body = objectMapper.writeValueAsString(java.util.Map.of("name", "api"));
      mockMvc.perform(post(base())
              .header(HttpHeaders.AUTHORIZATION, bearer())
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("rejects names that would escape the storage directory")
    void rejectsTraversalInName() throws Exception {
      for (var name : new String[] {"../escape", "..", ".", "a/b", "a\\b", "with space", ".hidden"}) {
        var body = objectMapper.writeValueAsString(java.util.Map.of("name", name));
        mockMvc.perform(post(base())
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
      }
      assertThat(repositories.count()).isZero();
    }

    @Test
    @DisplayName("rejects names Windows cannot use as a directory")
    void rejectsReservedNames() throws Exception {
      for (var name : new String[] {"con", "CON", "nul", "com1", "lpt3.txt"}) {
        var body = objectMapper.writeValueAsString(java.util.Map.of("name", name));
        mockMvc.perform(post(base())
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
      }
    }
  }

  @Nested
  @DisplayName("committing and reading")
  class Content {

    @Test
    @DisplayName("a committed file is readable back, with real authorship")
    void commitAndReadBlob() throws Exception {
      var id = createRepository("api");
      var commitId = commit(id, "src/main.java", "class Main {}", "Add Main");

      assertThat(commitId).hasSize(40);

      mockMvc.perform(get(base() + "/" + id + "/blob")
              .param("path", "src/main.java")
              .header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content").value("class Main {}"))
          .andExpect(jsonPath("$.data.binary").value(false))
          .andExpect(jsonPath("$.data.truncated").value(false));

      // Authorship comes from the caller's token, so history names a real identity.
      mockMvc.perform(get(base() + "/" + id + "/commits")
              .header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data[0].message").value("Add Main"))
          .andExpect(jsonPath("$.data[0].authorName").value("user-" + user.toString().substring(0, 8)))
          .andExpect(jsonPath("$.data[0].shortId").isNotEmpty());

      // No longer empty, which the list endpoint must reflect.
      mockMvc.perform(get(base() + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(jsonPath("$.data.empty").value(false));
    }

    @Test
    @DisplayName("a second commit to the same path replaces it rather than duplicating it")
    void secondCommitReplacesFile() throws Exception {
      var id = createRepository("api");
      commit(id, "README.md", "first", "Add readme");
      commit(id, "README.md", "second", "Update readme");

      mockMvc.perform(get(base() + "/" + id + "/blob")
              .param("path", "README.md")
              .header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(jsonPath("$.data.content").value("second"));

      mockMvc.perform(get(base() + "/" + id + "/tree")
              .header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("committing preserves files already in the tree")
    void commitPreservesExistingFiles() throws Exception {
      var id = createRepository("api");
      commit(id, "a.txt", "aaa", "Add a");
      commit(id, "b.txt", "bbb", "Add b");

      mockMvc.perform(get(base() + "/" + id + "/tree")
              .header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.length()").value(2));

      mockMvc.perform(get(base() + "/" + id + "/blob")
              .param("path", "a.txt")
              .header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(jsonPath("$.data.content").value("aaa"));
    }

    @Test
    @DisplayName("lists one directory level, directories before files")
    void treeListsOneLevel() throws Exception {
      var id = createRepository("api");
      commit(id, "README.md", "readme", "Add readme");
      commit(id, "src/main.java", "class Main {}", "Add main");
      commit(id, "src/deep/util.java", "class Util {}", "Add util");

      mockMvc.perform(get(base() + "/" + id + "/tree")
              .header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isOk())
          // The root holds one directory and one file, not three entries: the listing is not
          // recursive, so a large repository does not have to be loaded to browse it.
          .andExpect(jsonPath("$.data.length()").value(2))
          .andExpect(jsonPath("$.data[0].type").value("DIRECTORY"))
          .andExpect(jsonPath("$.data[0].name").value("src"))
          .andExpect(jsonPath("$.data[1].type").value("FILE"))
          .andExpect(jsonPath("$.data[1].size").value(6));

      mockMvc.perform(get(base() + "/" + id + "/tree")
              .param("path", "src")
              .header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.length()").value(2))
          .andExpect(jsonPath("$.data[0].name").value("deep"))
          .andExpect(jsonPath("$.data[1].name").value("main.java"));
    }

    @Test
    @DisplayName("a file larger than the limit is truncated, not loaded whole")
    void largeFileIsTruncated() throws Exception {
      var id = createRepository("api");
      // The test profile sets max-blob-bytes to 2048.
      var content = "x".repeat(5000);
      commit(id, "big.txt", content, "Add a big file");

      mockMvc.perform(get(base() + "/" + id + "/blob")
              .param("path", "big.txt")
              .header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.truncated").value(true))
          .andExpect(jsonPath("$.data.size").value(5000))
          .andExpect(jsonPath("$.data.binary").value(false))
          // Compared by value rather than with jsonPath's length(), which returns null for a string
          // and so silently asserts nothing at all.
          .andExpect(jsonPath("$.data.content").value("x".repeat(2048)));
    }

    @Test
    @DisplayName("a missing file is 404, not an empty success")
    void missingFileIsNotFound() throws Exception {
      var id = createRepository("api");
      commit(id, "README.md", "readme", "Add readme");

      mockMvc.perform(get(base() + "/" + id + "/blob")
              .param("path", "nope.txt")
              .header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("asking for a directory as a file is 404 rather than a confusing success")
    void directoryAsBlobIsNotFound() throws Exception {
      var id = createRepository("api");
      commit(id, "src/main.java", "class Main {}", "Add main");

      mockMvc.perform(get(base() + "/" + id + "/blob")
              .param("path", "src")
              .header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isNotFound());
    }
  }

  @Nested
  @DisplayName("branches and diffs")
  class BranchesAndDiffs {

    @Test
    @DisplayName("creates a branch from the default and lists both")
    void createBranch() throws Exception {
      var id = createRepository("api");
      commit(id, "README.md", "first", "Add readme");

      var body = objectMapper.writeValueAsString(java.util.Map.of("name", "feature/login"));
      mockMvc.perform(post(base() + "/" + id + "/branches")
              .header(HttpHeaders.AUTHORIZATION, bearer())
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.data.name").value("feature/login"));

      mockMvc.perform(get(base() + "/" + id + "/branches")
              .header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @DisplayName("a duplicate branch is 409")
    void duplicateBranchIsConflict() throws Exception {
      var id = createRepository("api");
      commit(id, "README.md", "first", "Add readme");

      var body = objectMapper.writeValueAsString(java.util.Map.of("name", "develop"));
      mockMvc.perform(post(base() + "/" + id + "/branches")
              .header(HttpHeaders.AUTHORIZATION, bearer())
              .contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isCreated());
      mockMvc.perform(post(base() + "/" + id + "/branches")
              .header(HttpHeaders.AUTHORIZATION, bearer())
              .contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("diffs two commits with real added and deleted line counts")
    void diffBetweenCommits() throws Exception {
      var id = createRepository("api");
      var first = commit(id, "a.txt", "line one\nline two\n", "Add a");
      var second = commit(id, "a.txt", "line one\nline two\nline three\n", "Extend a");

      mockMvc.perform(get(base() + "/" + id + "/diff")
              .param("from", first)
              .param("to", second)
              .header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.entries.length()").value(1))
          .andExpect(jsonPath("$.data.entries[0].changeType").value("MODIFY"))
          .andExpect(jsonPath("$.data.entries[0].newPath").value("a.txt"))
          .andExpect(jsonPath("$.data.entries[0].linesAdded").value(1))
          .andExpect(jsonPath("$.data.entries[0].linesDeleted").value(0));
    }

    @Test
    @DisplayName("an unknown ref is 404")
    void unknownRefIsNotFound() throws Exception {
      var id = createRepository("api");
      commit(id, "a.txt", "a", "Add a");

      mockMvc.perform(get(base() + "/" + id + "/tree")
              .param("ref", "no-such-branch")
              .header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isNotFound());
    }
  }

  @Nested
  @DisplayName("untrusted input (§37, threat model)")
  class UntrustedInput {

    /**
     * Paths that must never resolve.
     *
     * <p>Repository content is attacker-supplied in a product that hosts other people's code, so each
     * of these is a real attempt rather than a theoretical one. Note {@code ....//}, which defeats a
     * naive implementation that strips {@code ../} once.
     */
    @Test
    @DisplayName("rejects every form of path traversal rather than sanitising it")
    void rejectsPathTraversal() throws Exception {
      var id = createRepository("api");
      commit(id, "README.md", "readme", "Add readme");

      var hostile = new String[] {
        "../../../etc/passwd",
        "..",
        "../",
        "a/../../b",
        "....//etc/passwd",
        "./a",
        "a/./b",
        "a//b",
        "C:/Windows/System32/config/SAM",
        ".git/config",
        "a/.git/config",
        ".GIT/config",
      };

      for (var path : hostile) {
        mockMvc.perform(get(base() + "/" + id + "/blob")
                .param("path", path)
                .header(HttpHeaders.AUTHORIZATION, bearer()))
            .andExpect(status().isBadRequest());
      }
    }

    @Test
    @DisplayName("a leading slash is repository-relative, not a path into the host filesystem")
    void leadingSlashIsRepositoryRelative() throws Exception {
      var id = createRepository("api");
      commit(id, "README.md", "readme", "Add readme");

      // "/etc/passwd" is accepted as the repository-relative path "etc/passwd" rather than rejected,
      // because a UI commonly addresses files that way and the meaning is unambiguous. What matters
      // is that it resolves inside the repository, so it simply is not there.
      mockMvc.perform(get(base() + "/" + id + "/blob")
              .param("path", "/etc/passwd")
              .header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isNotFound());

      // And the same path with a leading slash finds the file that really is in the repository.
      mockMvc.perform(get(base() + "/" + id + "/blob")
              .param("path", "/README.md")
              .header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content").value("readme"));
    }

    @Test
    @DisplayName("rejects a NUL byte in a path")
    void rejectsNulByte() throws Exception {
      var id = createRepository("api");
      // A NUL truncates the string in some native calls, so a path that passed validation could
      // address a different file by the time it is used.
      mockMvc.perform(get(base() + "/" + id + "/blob")
              .param("path", "README.md\u0000.png")
              .header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("rejects refs using git's own special syntax")
    void rejectsHostileRefs() throws Exception {
      var id = createRepository("api");
      commit(id, "a.txt", "a", "Add a");

      var hostile = new String[] {
        "--upload-pack=touch /tmp/pwned",
        "-x",
        "HEAD@{1}",
        "main^",
        "main~2",
        "main..other",
        "refs/heads/main:refs/heads/other",
        "main\u0000",
      };

      for (var ref : hostile) {
        mockMvc.perform(get(base() + "/" + id + "/tree")
                .param("ref", ref)
                .header(HttpHeaders.AUTHORIZATION, bearer()))
            .andExpect(status().isBadRequest());
      }
    }

    @Test
    @DisplayName("a path that traverses is rejected on write as well as on read")
    void rejectsTraversalOnCommit() throws Exception {
      var id = createRepository("api");

      var body = objectMapper.writeValueAsString(java.util.Map.of(
          "path", "../../escaped.txt", "content", "x", "message", "nope"));
      mockMvc.perform(post(base() + "/" + id + "/files")
              .header(HttpHeaders.AUTHORIZATION, bearer())
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("binary content returns no text rather than mangled text")
    void binaryFileReportsNoContent() throws Exception {
      var id = createRepository("api");
      // A NUL byte is the practical binary signal, and it cannot be sent through the JSON text
      // endpoint — so the blob is written with JGit directly, as a real binary file would arrive.
      var entity = repositories.findById(id).orElseThrow();
      var directory = STORAGE_ROOT.resolve(organizationId.toString()).resolve(id + ".git");
      GitTestFixtures.commitBytes(
          directory, entity.getDefaultBranch(), "logo.png", new byte[] {(byte) 0x89, 0x50, 0x00, 0x01});

      mockMvc.perform(get(base() + "/" + id + "/blob")
              .param("path", "logo.png")
              .header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.binary").value(true))
          // Returning replacement characters would look like corruption of the file itself, which
          // sends whoever reads it after the wrong problem.
          .andExpect(jsonPath("$.data.content").doesNotExist());
    }
  }

  @Nested
  @DisplayName("authorization")
  class Authorization {

    @Test
    @DisplayName("a repository in another project is 404, not 403")
    void otherProjectIsNotFound() throws Exception {
      var id = createRepository("api");

      var otherProject = UUID.randomUUID();
      var otherBase = "/api/v1/organizations/" + organizationId + "/projects/" + otherProject
          + "/repositories/" + id;

      // 403 would confirm the id exists, turning this into an oracle for enumerating repository ids.
      mockMvc.perform(get(otherBase).header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isNotFound());
      mockMvc.perform(delete(otherBase).header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isNotFound());
      mockMvc.perform(get(otherBase + "/branches").header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isNotFound());

      assertThat(repositories.findById(id)).isPresent();
    }

    @Test
    @DisplayName("a caller without project access cannot see repositories")
    void deniedProjectAccessIsNotFound() throws Exception {
      createRepository("api");

      doThrow(new ResourceNotFoundException("Project not found"))
          .when(projectAccessClient)
          .requireProjectAccess(eq(organizationId), eq(projectId), any());

      mockMvc.perform(get(base()).header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an unreachable project-service fails closed with 503")
    void unreachableAuthorityFailsClosed() throws Exception {
      doThrow(new ProjectAccessClient.ProjectServiceUnavailableException(
              "Cannot verify project access right now. Please try again.",
              new RuntimeException("connection refused")))
          .when(projectAccessClient)
          .requireProjectAccess(any(), any(), any());

      // Treating an unavailable authority as permission would hand out access during an outage.
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

  @Nested
  @DisplayName("deleting")
  class Deleting {

    @Test
    @DisplayName("removes the row and the objects together")
    void deleteRemovesRowAndStorage() throws Exception {
      var id = createRepository("api");
      commit(id, "README.md", "readme", "Add readme");
      var directory = STORAGE_ROOT.resolve(organizationId.toString()).resolve(id + ".git");
      assertThat(directory).exists();

      mockMvc.perform(delete(base() + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().isOk());

      assertThat(repositories.findById(id)).isEmpty();
      // A soft delete would leave a row claiming the repository exists while its files are gone.
      assertThat(directory).doesNotExist();

      assertThat(outboxEvents.findAll())
          .anyMatch(row -> row.getEventType().equals(EventTypes.REPOSITORY_DELETED));
    }

    @Test
    @DisplayName("updating the description does not touch the objects")
    void updateDescription() throws Exception {
      var id = createRepository("api");
      commit(id, "README.md", "readme", "Add readme");

      var body = objectMapper.writeValueAsString(java.util.Map.of("description", "now documented"));
      mockMvc.perform(patch(base() + "/" + id)
              .header(HttpHeaders.AUTHORIZATION, bearer())
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.description").value("now documented"));

      mockMvc.perform(get(base() + "/" + id + "/blob")
              .param("path", "README.md")
              .header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(jsonPath("$.data.content").value("readme"));
    }
  }
}
