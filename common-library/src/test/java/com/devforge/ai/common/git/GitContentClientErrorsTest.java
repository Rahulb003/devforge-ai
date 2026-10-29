package com.devforge.ai.common.git;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.hamcrest.Matchers.startsWith;

import com.devforge.ai.common.exception.ResourceNotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.client.MockServerRestTemplateCustomizer;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

/**
 * How git-service's refusals reach the services that read repositories. A 400 for a ref or path
 * it cannot use used to escape as a 500 from review, docs, AI and pipelines alike.
 */
@DisplayName("GitContentClient errors")
class GitContentClientErrorsTest {

  private MockRestServiceServer server;
  private GitContentClient client;
  private final GitContentClient.Context context =
      new GitContentClient.Context(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

  @BeforeEach
  void setUp() {
    var customizer = new MockServerRestTemplateCustomizer();
    client = new GitContentClient(new RestTemplateBuilder(customizer), "http://git");
    server = customizer.getServer();
  }

  @Test
  @DisplayName("a ref git-service rejects is the caller's mistake, carrying git-service's reason")
  void badRequestIsTheCallersMistake() {
    server.expect(requestTo(startsWith("http://git/"))).andRespond(withStatus(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_JSON).body("{\"message\":\"Invalid ref: -1\"}"));

    assertThatThrownBy(() -> client.file(context, "-1", "a.py", "Bearer t"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid ref: -1");
  }

  @Test
  @DisplayName("a 400 without a readable body still says what was wrong")
  void badRequestWithoutBody() {
    server.expect(requestTo(startsWith("http://git/"))).andRespond(withStatus(HttpStatus.BAD_REQUEST));

    assertThatThrownBy(() -> client.file(context, "-1", "a.py", "Bearer t"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Not a valid branch, commit or path");
  }

  @Test
  @DisplayName("a repository the caller may not read is not found, never forbidden")
  void forbiddenIsNotFound() {
    server.expect(requestTo(startsWith("http://git/"))).andRespond(withStatus(HttpStatus.FORBIDDEN));

    assertThatThrownBy(() -> client.defaultBranch(context, "Bearer t"))
        .isInstanceOf(ResourceNotFoundException.class);
  }
}
