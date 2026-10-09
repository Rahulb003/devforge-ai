package com.devforge.ai.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("Metrics endpoint credential")
class MetricsEndpointFilterTest {

  private static MockHttpServletResponse scrape(String token, String authorization) throws Exception {
    var request = new MockHttpServletRequest("GET", "/actuator/prometheus");
    if (authorization != null) {
      request.addHeader("Authorization", authorization);
    }
    var response = new MockHttpServletResponse();
    new MetricsEndpointFilter(token).doFilter(request, response, new MockFilterChain());
    return response;
  }

  private static String basic(String user, String password) {
    return "Basic " + Base64.getEncoder()
        .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("with no token configured, refuses everyone")
  void failsClosedWithoutToken() throws Exception {
    assertThat(scrape("", basic("metrics", "")).getStatus()).isEqualTo(404);
    assertThat(scrape(null, null).getStatus()).isEqualTo(404);
  }

  @Test
  @DisplayName("passes only the right credential")
  void checksTheCredential() throws Exception {
    assertThat(scrape("s3cret", basic("metrics", "s3cret")).getStatus()).isEqualTo(200);
    assertThat(scrape("s3cret", basic("metrics", "guess")).getStatus()).isEqualTo(401);
    assertThat(scrape("s3cret", basic("admin", "s3cret")).getStatus()).isEqualTo(401);
    assertThat(scrape("s3cret", null).getStatus()).isEqualTo(401);
    assertThat(scrape("s3cret", "Basic not-base64!").getStatus()).isEqualTo(401);
  }

  @Test
  @DisplayName("leaves every other path alone")
  void otherPathsUntouched() throws Exception {
    var request = new MockHttpServletRequest("GET", "/actuator/health");
    var response = new MockHttpServletResponse();
    new MetricsEndpointFilter("").doFilter(request, response, new MockFilterChain());
    assertThat(response.getStatus()).isEqualTo(200);
  }
}
