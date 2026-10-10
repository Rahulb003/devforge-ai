package com.devforge.ai.aiservice;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devforge.ai.common.git.GitContentClient;
import com.devforge.ai.common.security.client.ProjectAccessClient;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Without an API key: an honest 503, and nothing is read or called on the way. */
@SpringBootTest(properties = "devforge.ai.api-key=")
@AutoConfigureMockMvc
@DisplayName("AI assistance without an API key")
class UnconfiguredTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ProjectAccessClient projectAccess;
  @MockitoBean private GitContentClient git;

  @Test
  @DisplayName("explaining answers 503 'not configured', and the status says so")
  void notConfigured() throws Exception {
    var bearer = "Bearer " + TestTokens.accessToken(UUID.randomUUID());
    mockMvc.perform(post("/api/v1/organizations/" + UUID.randomUUID() + "/projects/" + UUID.randomUUID()
            + "/repositories/" + UUID.randomUUID() + "/ai/explain")
            .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
            .content("{\"path\":\"a.txt\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.message").value("AI assistance is not configured on this deployment"));
    mockMvc.perform(get("/api/v1/ai/status").header("Authorization", bearer))
        .andExpect(jsonPath("$.data.configured").value(false));
    Mockito.verifyNoInteractions(git);
  }
}
