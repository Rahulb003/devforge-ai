package com.devforge.ai.gitservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devforge.ai.common.events.outbox.OutboxEventRepository;
import com.devforge.ai.common.exception.ResourceNotFoundException;
import com.devforge.ai.common.security.client.ProjectAccessClient;
import com.devforge.ai.gitservice.http.PersonalTokenExchangeClient;
import com.devforge.ai.gitservice.repository.GitRepositoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Git's own client against the real servlet, on a real port: clone, push, and every refusal.
 *
 * <p>Only the two calls to other services are stubbed - the token exchange and the project-access
 * check. Repositories, refs, packs and the outbox are real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@DisplayName("Git over HTTP")
class GitHttpProtocolTest {

  static final Path STORAGE_ROOT;

  static {
    try {
      STORAGE_ROOT = Files.createTempDirectory("devforge-git-http-test");
      STORAGE_ROOT.toFile().deleteOnExit();
    } catch (java.io.IOException ex) {
      throw new IllegalStateException(ex);
    }
  }

  @DynamicPropertySource
  static void storage(DynamicPropertyRegistry registry) {
    registry.add("devforge.git.storage-root", STORAGE_ROOT::toString);
  }

  /** Personal tokens the stubbed exchange recognises. */
  static final String DEVELOPER_TOKEN = "dfp_developer";
  static final String VIEWER_TOKEN = "dfp_viewer";
  static final String OUTSIDER_TOKEN = "dfp_outsider";

  @LocalServerPort private int port;
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private GitRepositoryRepository repositories;
  @Autowired private OutboxEventRepository outboxEvents;
  @MockitoBean private ProjectAccessClient projectAccessClient;
  @MockitoBean private PersonalTokenExchangeClient exchangeClient;

  @TempDir Path workspace;

  private final UUID organizationId = UUID.randomUUID();
  private final UUID projectId = UUID.randomUUID();
  private final UUID developer = UUID.randomUUID();
  private final UUID viewer = UUID.randomUUID();
  private final UUID outsider = UUID.randomUUID();
  private final String developerAccess = TestTokens.accessToken(developer);
  private final String viewerAccess = TestTokens.accessToken(viewer);
  private final String outsiderAccess = TestTokens.accessToken(outsider);
  private UUID repo;

  @BeforeEach
  void setUp() throws Exception {
    repositories.deleteAll();
    outboxEvents.deleteAll();
    when(exchangeClient.exchange(anyString())).thenReturn(Optional.empty());
    when(exchangeClient.exchange(DEVELOPER_TOKEN)).thenReturn(Optional.of(developerAccess));
    when(exchangeClient.exchange(VIEWER_TOKEN)).thenReturn(Optional.of(viewerAccess));
    when(exchangeClient.exchange(OUTSIDER_TOKEN)).thenReturn(Optional.of(outsiderAccess));
    // A viewer reads but may not write; an outsider is not a member, so sees nothing at all.
    doThrow(new AccessDeniedException("read-only")).when(projectAccessClient).requireProjectAccess(
        any(), any(), argThat(header -> header != null && header.endsWith(viewerAccess)),
        eq(ProjectAccessClient.Access.WRITE));
    doThrow(new ResourceNotFoundException("Project not found")).when(projectAccessClient)
        .requireProjectAccess(any(), any(),
            argThat(header -> header != null && header.endsWith(outsiderAccess)), any());

    var created = data(mockMvc.perform(post(base()).header(HttpHeaders.AUTHORIZATION, "Bearer " + developerAccess)
        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"over-http\"}")).andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString());
    repo = UUID.fromString(created.path("id").asText());
    // A first commit through the API, so there is a main branch to clone.
    mockMvc.perform(post(base() + "/" + repo + "/files").header(HttpHeaders.AUTHORIZATION, "Bearer " + developerAccess)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("path", "README.md", "content", "hello\n", "message", "Start"))))
        .andExpect(status().isCreated());
    outboxEvents.deleteAll();
  }

  private String base() {
    return "/api/v1/organizations/" + organizationId + "/projects/" + projectId + "/repositories";
  }

  private String cloneUrl() {
    return "http://localhost:" + port + "/api/v1/git/" + organizationId + "/" + projectId + "/" + repo + ".git";
  }

  private JsonNode data(String body) throws Exception {
    return objectMapper.readTree(body).path("data");
  }

  private static UsernamePasswordCredentialsProvider as(String token) {
    return new UsernamePasswordCredentialsProvider("anyone", token);
  }

  private Git cloneAs(String token, String directory) throws Exception {
    return Git.cloneRepository().setURI(cloneUrl()).setDirectory(workspace.resolve(directory).toFile())
        .setCredentialsProvider(as(token)).call();
  }

  private void commitFile(Git git, String path, String content) throws Exception {
    var file = git.getRepository().getWorkTree().toPath().resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
    git.add().addFilepattern(path).call();
    git.commit().setMessage("Add " + path).setAuthor("Dev", "dev@example.com").setCommitter("Dev", "dev@example.com").call();
  }

  private RemoteRefUpdate push(Git git, String token, String refSpec) throws Exception {
    var results = git.push().setCredentialsProvider(as(token)).setRefSpecs(new RefSpec(refSpec)).call();
    var update = results.iterator().next().getRemoteUpdates().iterator().next();
    return update;
  }

  private String blob(String ref, String path) throws Exception {
    return data(mockMvc.perform(get(base() + "/" + repo + "/blob").param("ref", ref).param("path", path)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + developerAccess))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("content").asText();
  }

  private HttpResponse<String> raw(String path, String token) throws Exception {
    var request = HttpRequest.newBuilder(URI.create(cloneUrl() + path));
    if (token != null) {
      request.header("Authorization", "Basic " + Base64.getEncoder()
          .encodeToString(("anyone:" + token).getBytes(StandardCharsets.UTF_8)));
    }
    return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  @DisplayName("a member clones what was committed in the UI")
  void cloneSeesCommittedWork() throws Exception {
    try (var git = cloneAs(DEVELOPER_TOKEN, "clone")) {
      // Line endings as the checkout wrote them: a Windows machine with autocrlf adds a carriage return.
      assertThat(Files.readString(git.getRepository().getWorkTree().toPath().resolve("README.md"))
          .replace("\r", "")).isEqualTo("hello\n");
      assertThat(git.getRepository().getBranch()).isEqualTo("main");
    }
  }

  @Test
  @DisplayName("a push lands on the branch, shows in the API, and publishes one event with its commit count")
  void pushLandsAndPublishes() throws Exception {
    try (var git = cloneAs(DEVELOPER_TOKEN, "push")) {
      commitFile(git, "src/app.ts", "export const a = 1;\n");
      commitFile(git, "src/b.ts", "export const b = 2;\n");

      assertThat(push(git, DEVELOPER_TOKEN, "refs/heads/main:refs/heads/main").getStatus())
          .isEqualTo(RemoteRefUpdate.Status.OK);
    }

    assertThat(blob("main", "src/b.ts")).isEqualTo("export const b = 2;\n");
    var events = outboxEvents.findAll();
    assertThat(events).singleElement().satisfies(event -> {
      assertThat(event.getEventType()).isEqualTo("RepositoryPushed");
      var payload = objectMapper.readTree(event.getPayload()).path("payload");
      assertThat(payload.path("via").asText()).isEqualTo("git");
      assertThat(payload.path("commitCount").asInt()).isEqualTo(2);
      assertThat(payload.path("branch").asText()).isEqualTo("main");
      // The actor is the token's owner, not the service.
      assertThat(objectMapper.readTree(event.getPayload()).path("actorId").asText())
          .isEqualTo(developer.toString());
    });
  }

  @Test
  @DisplayName("a viewer can clone but not push")
  void viewerIsReadOnly() throws Exception {
    try (var git = cloneAs(VIEWER_TOKEN, "viewer")) {
      commitFile(git, "sneaky.txt", "nope\n");
      assertThatThrownBy(() -> push(git, VIEWER_TOKEN, "refs/heads/main:refs/heads/main"))
          .isInstanceOf(TransportException.class);
    }
    assertThat(outboxEvents.findAll()).isEmpty();
    // Refused before any credentials are asked for again: 403, not 401.
    assertThat(raw("/info/refs?service=git-receive-pack", VIEWER_TOKEN).statusCode()).isEqualTo(403);
  }

  @Test
  @DisplayName("someone outside the project cannot tell the repository exists")
  void outsiderSeesNotFound() throws Exception {
    assertThat(raw("/info/refs?service=git-upload-pack", OUTSIDER_TOKEN).statusCode()).isEqualTo(404);
    // The same as a repository that really does not exist.
    var missing = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
            "http://localhost:" + port + "/api/v1/git/" + organizationId + "/" + projectId + "/"
                + UUID.randomUUID() + ".git/info/refs?service=git-upload-pack"))
        .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
            ("x:" + DEVELOPER_TOKEN).getBytes(StandardCharsets.UTF_8))).build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(missing.statusCode()).isEqualTo(404);
  }

  @Test
  @DisplayName("no token, or a bad one, is challenged so git asks for credentials")
  void missingCredentialsAreChallenged() throws Exception {
    var anonymous = raw("/info/refs?service=git-upload-pack", null);
    assertThat(anonymous.statusCode()).isEqualTo(401);
    assertThat(anonymous.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(
        value -> assertThat(value).startsWith("Basic realm=\"DevForge\""));
    assertThat(raw("/info/refs?service=git-upload-pack", "dfp_wrong").statusCode()).isEqualTo(401);

    assertThatThrownBy(() -> cloneAs("dfp_wrong", "bad")).isInstanceOf(TransportException.class);
  }

  @Test
  @DisplayName("the default branch cannot be deleted or rewritten; other branches can")
  void defaultBranchIsProtected() throws Exception {
    try (var git = cloneAs(DEVELOPER_TOKEN, "rules")) {
      // A rewritten main: a new root commit that does not contain the old history.
      git.checkout().setOrphan(true).setName("rewrite").call();
      commitFile(git, "other.txt", "rewritten\n");
      var forced = push(git, DEVELOPER_TOKEN, "+refs/heads/rewrite:refs/heads/main");
      assertThat(forced.getStatus()).isEqualTo(RemoteRefUpdate.Status.REJECTED_OTHER_REASON);
      assertThat(forced.getMessage()).contains("cannot be rewritten");

      var deleted = push(git, DEVELOPER_TOKEN, ":refs/heads/main");
      assertThat(deleted.getStatus()).isEqualTo(RemoteRefUpdate.Status.REJECTED_OTHER_REASON);
      assertThat(deleted.getMessage()).contains("cannot be deleted");

      // The same history may go anywhere else, and be force-pushed there.
      assertThat(push(git, DEVELOPER_TOKEN, "+refs/heads/rewrite:refs/heads/experiment").getStatus())
          .isEqualTo(RemoteRefUpdate.Status.OK);
      assertThat(push(git, DEVELOPER_TOKEN, ":refs/heads/experiment").getStatus())
          .isEqualTo(RemoteRefUpdate.Status.OK);

      // Only branches and tags: nothing else in the ref namespace is writable.
      assertThat(push(git, DEVELOPER_TOKEN, "refs/heads/rewrite:refs/notes/commits").getStatus())
          .isEqualTo(RemoteRefUpdate.Status.REJECTED_OTHER_REASON);
    }
    assertThat(blob("main", "README.md")).isEqualTo("hello\n");
  }

  @Test
  @DisplayName("while approvals are required, main changes only through a pull request")
  void mergeRuleHoldsForPushes() throws Exception {
    var entity = repositories.findByIdAndProjectId(repo, projectId).orElseThrow();
    entity.setRequiredApprovals(1);
    repositories.save(entity);

    try (var git = cloneAs(DEVELOPER_TOKEN, "ruled")) {
      commitFile(git, "change.txt", "direct\n");
      var direct = push(git, DEVELOPER_TOKEN, "refs/heads/main:refs/heads/main");
      assertThat(direct.getStatus()).isEqualTo(RemoteRefUpdate.Status.REJECTED_OTHER_REASON);
      assertThat(direct.getMessage()).contains("needs 1 approval");

      assertThat(push(git, DEVELOPER_TOKEN, "refs/heads/main:refs/heads/feature/change").getStatus())
          .isEqualTo(RemoteRefUpdate.Status.OK);
    }
    assertThat(blob("feature/change", "change.txt")).isEqualTo("direct\n");
  }

  @Test
  @DisplayName("the repository's files are never served directly")
  void dumbProtocolIsOff() throws Exception {
    // The dumb protocol would hand these out as files, config and hooks included.
    for (var path : new String[] {"/HEAD", "/config", "/objects/info/packs", "/info/refs"}) {
      var response = raw(path, DEVELOPER_TOKEN);
      // 406 is JGit refusing a dumb-protocol ref listing: info/refs asked for without a service.
      assertThat(response.statusCode()).as(path).isIn(403, 404, 406);
    }
  }
}
