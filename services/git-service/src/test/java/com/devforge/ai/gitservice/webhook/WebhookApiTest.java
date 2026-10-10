package com.devforge.ai.gitservice.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devforge.ai.common.security.client.ProjectAccessClient;
import com.devforge.ai.gitservice.TestTokens;
import com.devforge.ai.gitservice.repository.GitRepositoryRepository;
import com.devforge.ai.gitservice.repository.WebhookRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Webhooks end to end: a commit through the API reaches a real HTTP receiver, signed.
 *
 * <p>The receiver is on localhost, so the test configuration allows private addresses and plain
 * http; {@link WebhookUrlGuardTest} covers what production refuses.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Webhook API")
class WebhookApiTest {

  static final java.nio.file.Path STORAGE_ROOT;

  static {
    try {
      STORAGE_ROOT = java.nio.file.Files.createTempDirectory("devforge-webhook-test");
      STORAGE_ROOT.toFile().deleteOnExit();
    } catch (java.io.IOException ex) {
      throw new IllegalStateException("Could not create a temporary storage root", ex);
    }
  }

  @DynamicPropertySource
  static void storage(DynamicPropertyRegistry registry) {
    registry.add("devforge.git.storage-root", STORAGE_ROOT::toString);
  }

  record Received(String body, String signature, String event) {}

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private GitRepositoryRepository repositories;
  @Autowired private WebhookRepository webhooks;
  @MockitoBean private ProjectAccessClient projectAccessClient;

  private final UUID organizationId = UUID.randomUUID();
  private final UUID projectId = UUID.randomUUID();
  private final UUID user = UUID.randomUUID();
  private final LinkedBlockingQueue<Received> received = new LinkedBlockingQueue<>();
  private HttpServer receiver;

  private String base() {
    return "/api/v1/organizations/" + organizationId + "/projects/" + projectId + "/repositories";
  }

  private String bearer() {
    return "Bearer " + TestTokens.accessToken(user);
  }

  @BeforeEach
  void setUp() throws Exception {
    webhooks.deleteAll();
    repositories.deleteAll();
    receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    receiver.createContext("/hook", exchange -> {
      var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      received.add(new Received(body, exchange.getRequestHeaders().getFirst("X-DevForge-Signature"),
          exchange.getRequestHeaders().getFirst("X-DevForge-Event")));
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
    });
    receiver.start();
  }

  @AfterEach
  void tearDown() {
    receiver.stop(0);
  }

  private String hookUrl() {
    return "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook";
  }

  private UUID createRepository() throws Exception {
    var response = mockMvc.perform(post(base())
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("name", "hooks", "description", "d"))))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return UUID.fromString(objectMapper.readTree(response).path("data").path("id").asText());
  }

  private String createWebhook(UUID repositoryId) throws Exception {
    var response = mockMvc.perform(post(base() + "/" + repositoryId + "/webhooks")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("url", hookUrl()))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.webhook.url").value(hookUrl()))
        .andReturn().getResponse().getContentAsString();
    return objectMapper.readTree(response).path("data").path("secret").asText();
  }

  @Test
  @DisplayName("a commit is delivered to the receiver, signed with the webhook's secret")
  void commitIsDeliveredSigned() throws Exception {
    var repositoryId = createRepository();
    var secret = createWebhook(repositoryId);
    assertThat(secret).startsWith("whsec_");

    mockMvc.perform(post(base() + "/" + repositoryId + "/files")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(
                Map.of("path", "README.md", "content", "# hi\n", "message", "first"))))
        .andExpect(status().isCreated());

    var delivery = received.poll(10, TimeUnit.SECONDS);
    assertThat(delivery).as("a delivery reached the receiver").isNotNull();
    assertThat(delivery.event()).isEqualTo("push");
    assertThat(delivery.signature()).isEqualTo("sha256=" + WebhookDispatcher.sign(secret, delivery.body()));
    var payload = objectMapper.readTree(delivery.body());
    assertThat(payload.path("repositoryId").asText()).isEqualTo(repositoryId.toString());
    assertThat(payload.path("branch").asText()).isEqualTo("main");
    assertThat(payload.path("via").asText()).isEqualTo("editor");
    assertThat(payload.path("commitId").asText()).hasSize(40);

    // The outcome is recorded for the admin, and the secret is never listed again.
    long deadline = System.currentTimeMillis() + 5000;
    while (System.currentTimeMillis() < deadline
        && webhooks.findByRepositoryIdOrderByCreatedAtAsc(repositoryId).get(0).getLastStatus() == null) {
      Thread.onSpinWait();
    }
    var listed = mockMvc.perform(get(base() + "/" + repositoryId + "/webhooks")
            .header(HttpHeaders.AUTHORIZATION, bearer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].lastStatus").value(204))
        .andReturn().getResponse().getContentAsString();
    assertThat(listed).doesNotContain(secret).doesNotContain("secret");
  }

  @Test
  @DisplayName("only project admins manage webhooks")
  void adminsOnly() throws Exception {
    var repositoryId = createRepository();
    doThrow(new AccessDeniedException("Admins only")).when(projectAccessClient)
        .requireProjectAccess(eq(organizationId), eq(projectId), any(), eq(ProjectAccessClient.Access.ADMIN));

    mockMvc.perform(post(base() + "/" + repositoryId + "/webhooks")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("url", hookUrl()))))
        .andExpect(status().isForbidden());
    mockMvc.perform(get(base() + "/" + repositoryId + "/webhooks")
            .header(HttpHeaders.AUTHORIZATION, bearer()))
        .andExpect(status().isForbidden());
    assertThat(webhooks.count()).isZero();
  }

  @Test
  @DisplayName("a webhook is deleted only within its own repository")
  void deleteIsScoped() throws Exception {
    var repositoryId = createRepository();
    createWebhook(repositoryId);
    var id = webhooks.findByRepositoryIdOrderByCreatedAtAsc(repositoryId).get(0).getId();

    mockMvc.perform(delete(base() + "/" + UUID.randomUUID() + "/webhooks/" + id)
            .header(HttpHeaders.AUTHORIZATION, bearer()))
        .andExpect(status().isNotFound());
    mockMvc.perform(delete(base() + "/" + repositoryId + "/webhooks/" + id)
            .header(HttpHeaders.AUTHORIZATION, bearer()))
        .andExpect(status().isOk());
    assertThat(webhooks.count()).isZero();
  }

  @Test
  @DisplayName("an address the guard refuses is a 400, not a stored webhook")
  void refusedUrlIsBadRequest() throws Exception {
    var repositoryId = createRepository();
    mockMvc.perform(post(base() + "/" + repositoryId + "/webhooks")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("url", "ftp://example.com/hook"))))
        .andExpect(status().isBadRequest());
    assertThat(webhooks.count()).isZero();
  }
}
