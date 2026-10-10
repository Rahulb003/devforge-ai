package com.devforge.ai.aiservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devforge.ai.common.git.GitContentClient;
import com.devforge.ai.common.git.RepositoryFile;
import com.devforge.ai.common.security.client.ProjectAccessClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Asking about a repository: lexical retrieval, then the model. The live call is UNVERIFIED. */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext
@DisplayName("AI questions about a repository")
class AskApiTest {

  static final HttpServer MODEL;
  static final AtomicReference<String> LAST_REQUEST = new AtomicReference<>();

  static {
    try {
      MODEL = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      MODEL.createContext("/v1/messages", exchange -> {
        LAST_REQUEST.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        var bytes = "{\"content\":[{\"type\":\"text\",\"text\":\"In src/auth/token.ts.\"}]}"
            .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
      });
      MODEL.start();
    } catch (java.io.IOException ex) {
      throw new IllegalStateException(ex);
    }
  }

  @AfterAll
  static void stop() {
    MODEL.stop(0);
  }

  @DynamicPropertySource
  static void model(DynamicPropertyRegistry registry) {
    registry.add("devforge.ai.base-url", () -> "http://127.0.0.1:" + MODEL.getAddress().getPort());
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @MockitoBean private ProjectAccessClient projectAccess;
  @MockitoBean private GitContentClient git;

  private final UUID repositoryId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    LAST_REQUEST.set(null);
    when(git.defaultBranch(any(), any())).thenReturn("main");
    when(git.filesToAnalyse(any(), eq("main"), eq(null), any())).thenReturn(List.of(
        RepositoryFile.of("src/auth/token.ts", 80, false, false,
            "export function validateToken(token) {\n  return verify(token);\n}\n"),
        RepositoryFile.of("README.md", 20, false, false, "# Demo\nA small project.\n"),
        RepositoryFile.of("logo.png", 10, false, true, "")));
  }

  private ResultActions ask(String question) throws Exception {
    return mockMvc.perform(post("/api/v1/organizations/" + UUID.randomUUID() + "/projects/" + UUID.randomUUID()
            + "/repositories/" + repositoryId + "/ai/ask")
        .header("Authorization", "Bearer " + TestTokens.accessToken(UUID.randomUUID()))
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(java.util.Map.of("question", question))));
  }

  @Test
  @DisplayName("the matching files are sent as untrusted excerpts and named as sources")
  void answersFromMatchingFiles() throws Exception {
    ask("Where is the token validated?")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.answer").value("In src/auth/token.ts."))
        .andExpect(jsonPath("$.data.sources.length()").value(1))
        .andExpect(jsonPath("$.data.sources[0]").value("src/auth/token.ts"))
        .andExpect(jsonPath("$.data.filesSearched").value(3));

    var user = objectMapper.readTree(LAST_REQUEST.get()).path("messages").get(0).path("content").asText();
    assertThat(user).startsWith("<repository>\n<file path=\"src/auth/token.ts\">")
        .contains("validateToken").doesNotContain("README")
        .endsWith("</repository>\n\nQuestion: Where is the token validated?");
  }

  @Test
  @DisplayName("when nothing matches, it says so without calling the model")
  void nothingMatches() throws Exception {
    ask("Where is the kubernetes operator?")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sources.length()").value(0))
        .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.startsWith("Nothing in the repository matched")));
    assertThat(LAST_REQUEST.get()).isNull();
  }

  @Test
  @DisplayName("a question is required and length-limited")
  void questionValidation() throws Exception {
    ask("   ").andExpect(status().isBadRequest());
    ask("x".repeat(501)).andExpect(status().isBadRequest());
  }
}
