package com.devforge.ai.aiservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import java.util.Map;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** A proposed change to a file: generated, returned, never written. The live call is UNVERIFIED. */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext
@DisplayName("AI change suggestions")
class SuggestApiTest {

  static final HttpServer MODEL;
  static final AtomicReference<String> LAST_REQUEST = new AtomicReference<>();

  static {
    try {
      MODEL = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      MODEL.createContext("/v1/messages", exchange -> {
        LAST_REQUEST.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        // Fenced, as models sometimes do despite the instruction: the fence must not reach the file.
        var bytes = "{\"content\":[{\"type\":\"text\",\"text\":\"```ts\\nexport const add = (a: number, b: number) => a + b;\\n```\"}]}"
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

  @BeforeEach
  void setUp() {
    LAST_REQUEST.set(null);
    when(git.defaultBranch(any(), any())).thenReturn("main");
    when(git.file(any(), eq("main"), eq("src/add.ts"), any()))
        .thenReturn(RepositoryFile.of("src/add.ts", 40, false, false, "export const add = (a, b) => a + b;\n"));
  }

  private ResultActions suggest(String path, String request) throws Exception {
    return mockMvc.perform(post("/api/v1/organizations/" + UUID.randomUUID() + "/projects/" + UUID.randomUUID()
            + "/repositories/" + UUID.randomUUID() + "/ai/suggest")
        .header("Authorization", "Bearer " + TestTokens.accessToken(UUID.randomUUID()))
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(Map.of("path", path, "request", request))));
  }

  @Test
  @DisplayName("returns the proposed file, unfenced, asking for a whole-file answer with room for it")
  void proposes() throws Exception {
    suggest("src/add.ts", "Add TypeScript types")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.proposed").value("export const add = (a: number, b: number) => a + b;\n"))
        .andExpect(jsonPath("$.data.path").value("src/add.ts"));

    var sent = objectMapper.readTree(LAST_REQUEST.get());
    assertThat(sent.path("max_tokens").asInt()).isEqualTo(8000);
    assertThat(sent.path("system").asText()).contains("complete new content of the file");
    assertThat(sent.path("messages").get(0).path("content").asText())
        .contains("<file>\nexport const add").endsWith("Request: Add TypeScript types");
  }

  @Test
  @DisplayName("needs write access, and refuses files it could only see part of")
  void refusals() throws Exception {
    doThrow(new AccessDeniedException("read-only")).when(projectAccess)
        .requireProjectAccess(any(), any(), any(), eq(ProjectAccessClient.Access.WRITE));
    suggest("src/add.ts", "Add types").andExpect(status().isForbidden());

    org.mockito.Mockito.reset(projectAccess);
    when(git.file(any(), eq("main"), eq("big.ts"), any()))
        .thenReturn(RepositoryFile.of("big.ts", 900_000, true, false, "partial"));
    suggest("big.ts", "Rename things").andExpect(status().isBadRequest());
    suggest("src/add.ts", " ").andExpect(status().isBadRequest());
    assertThat(LAST_REQUEST.get()).isNull();
  }
}
