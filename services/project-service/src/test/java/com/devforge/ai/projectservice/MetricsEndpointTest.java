package com.devforge.ai.projectservice;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** The scrape endpoint in a real service: security, the credential filter and the registry together. */
@SpringBootTest
@AutoConfigureMockMvc
// Spring Boot switches metric exporters off in tests unless asked; without this the endpoint 404s.
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@TestPropertySource(properties = {
    "devforge.metrics.token=scrape-me",
    "management.endpoints.web.exposure.include=health,info,prometheus"})
@DisplayName("Prometheus endpoint")
class MetricsEndpointTest {

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("serves metrics to the scrape credential, and to nobody else")
  void scrapeNeedsTheCredential() throws Exception {
    mockMvc.perform(get("/actuator/prometheus").with(request -> {
          request.addHeader("Authorization", "Basic " + java.util.Base64.getEncoder()
              .encodeToString("metrics:scrape-me".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
          return request;
        }))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("jvm_memory_used_bytes")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("http_server_requests")));

    mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
    // A user's token is not the scrape credential.
    mockMvc.perform(get("/actuator/prometheus")
            .header("Authorization", "Bearer " + TestTokens.accessToken(java.util.UUID.randomUUID())))
        .andExpect(status().isUnauthorized());
  }
}
