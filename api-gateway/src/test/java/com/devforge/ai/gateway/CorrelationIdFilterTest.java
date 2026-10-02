package com.devforge.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The correlation id is what lets one request be followed across services, and it is also a
 * caller-controlled string that reaches log files. Both properties are tested here.
 */
class CorrelationIdFilterTest {

  private final CorrelationIdFilter filter = new CorrelationIdFilter();

  @Test
  @DisplayName("a request without an id is given one")
  void generatesWhenAbsent() throws Exception {
    var request = new MockHttpServletRequest();
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, Mockito.mock(FilterChain.class));

    assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isNotBlank();
  }

  @Test
  @DisplayName("an inbound id is reused so the trace continues")
  void reusesInboundId() throws Exception {
    var request = new MockHttpServletRequest();
    request.addHeader(CorrelationIdFilter.HEADER, "req-12345");
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, Mockito.mock(FilterChain.class));

    assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("req-12345");
  }

  @Test
  @DisplayName("a malformed id is replaced rather than echoed")
  void rejectsMalformedId() throws Exception {
    var request = new MockHttpServletRequest();
    // Newlines would forge extra log lines; the value lands in the MDC and in
    // every log statement for the request.
    request.addHeader(CorrelationIdFilter.HEADER, "abc\ninjected FATAL something");
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, Mockito.mock(FilterChain.class));

    var issued = response.getHeader(CorrelationIdFilter.HEADER);
    assertThat(issued).doesNotContain("injected").doesNotContain("\n");
  }

  @Test
  @DisplayName("an over-long id is replaced")
  void rejectsOverLongId() throws Exception {
    var request = new MockHttpServletRequest();
    request.addHeader(CorrelationIdFilter.HEADER, "x".repeat(500));
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, Mockito.mock(FilterChain.class));

    assertThat(response.getHeader(CorrelationIdFilter.HEADER)).hasSizeLessThanOrEqualTo(64);
  }

  @Test
  @DisplayName("the id is cleared from the MDC after the request")
  void clearsMdc() throws Exception {
    var request = new MockHttpServletRequest();
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, Mockito.mock(FilterChain.class));

    // Threads are pooled: a leftover id would be attributed to the next,
    // unrelated request that happens to reuse the thread.
    assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
  }

  @Test
  @DisplayName("the id is present in the MDC while the chain runs")
  void presentDuringChain() throws Exception {
    var request = new MockHttpServletRequest();
    request.addHeader(CorrelationIdFilter.HEADER, "during-chain");
    var response = new MockHttpServletResponse();

    var seen = new String[1];
    FilterChain chain = (req, res) -> seen[0] = MDC.get(CorrelationIdFilter.MDC_KEY);

    filter.doFilter(request, response, chain);

    assertThat(seen[0]).isEqualTo("during-chain");
  }
}
