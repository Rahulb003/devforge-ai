package com.devforge.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.client.ResourceAccessException;

/**
 * Oversized bodies are refused at the edge, and a service that drops the connection is a 502 -
 * both were 500s, found by sending every write route a 5.7 MB body.
 */
@DisplayName("Gateway request size and upstream errors")
class RequestSizeFilterTest {

  private final RequestSizeFilter filter = new RequestSizeFilter(1000, 5000);

  private MockHttpServletResponse send(String path, int bytes) throws Exception {
    var request = new MockHttpServletRequest("POST", path);
    request.setContent(new byte[bytes]);
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    response.setHeader("X-Forwarded", chain.getRequest() == null ? "no" : "yes");
    return response;
  }

  @Test
  @DisplayName("a body over the limit is a 413 and is never forwarded")
  void refusesOversizedBody() throws Exception {
    var response = send("/api/v1/organizations/o/projects/p/tasks", 1001);
    assertThat(response.getStatus()).isEqualTo(413);
    assertThat(response.getHeader("X-Forwarded")).isEqualTo("no");
    assertThat(response.getContentAsString()).contains("Request body too large");
  }

  @Test
  @DisplayName("a body within the limit goes through")
  void forwardsNormalBody() throws Exception {
    var response = send("/api/v1/organizations/o/projects/p/tasks", 1000);
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("X-Forwarded")).isEqualTo("yes");
  }

  @Test
  @DisplayName("git pushes get the larger push limit")
  void gitPushesHaveTheirOwnLimit() throws Exception {
    assertThat(send("/api/v1/git/o/p/r.git/git-receive-pack", 4000).getHeader("X-Forwarded"))
        .isEqualTo("yes");
    assertThat(send("/api/v1/git/o/p/r.git/git-receive-pack", 5001).getStatus()).isEqualTo(413);
  }

  @Test
  @DisplayName("a service that cannot be reached is a 502, not a 500")
  void unreachableUpstreamIsBadGateway() {
    var request = new MockHttpServletRequest("POST", "/api/v1/organizations/o/projects/p/tasks");
    var response = new UpstreamErrorHandler()
        .upstreamUnreachable(new ResourceAccessException("connection aborted"), request);
    assertThat(response.getStatusCode().value()).isEqualTo(502);
    assertThat(response.getBody().getMessage()).contains("did not answer");
  }
}
