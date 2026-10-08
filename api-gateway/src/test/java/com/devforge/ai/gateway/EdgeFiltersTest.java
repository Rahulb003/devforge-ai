package com.devforge.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("Gateway edge filters")
class EdgeFiltersTest {

  /** A clock the test can move forward. */
  static final class MutableClock extends Clock {
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-09T10:00:05Z"));

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now.get();
    }
  }

  private static MockHttpServletResponse call(
      AuthRateLimitFilter filter, String method, String path, String ip, String forwardedFor)
      throws Exception {
    var request = new MockHttpServletRequest(method, path);
    request.setRemoteAddr(ip);
    if (forwardedFor != null) {
      request.addHeader("X-Forwarded-For", forwardedFor);
    }
    var response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    return response;
  }

  @Nested
  @DisplayName("AuthRateLimitFilter")
  class RateLimit {

    @Test
    @DisplayName("allows the limit, then answers 429 with Retry-After")
    void throttlesPastTheLimit() throws Exception {
      var filter = new AuthRateLimitFilter(new MutableClock(), 3, false);
      for (int i = 0; i < 3; i++) {
        assertThat(call(filter, "POST", "/api/v1/auth/login", "10.0.0.1", null).getStatus())
            .isEqualTo(200);
      }
      var refused = call(filter, "POST", "/api/v1/auth/login", "10.0.0.1", null);

      assertThat(refused.getStatus()).isEqualTo(429);
      assertThat(refused.getHeader("Retry-After")).isEqualTo("55");
      assertThat(refused.getContentAsString()).contains("Too many attempts");
    }

    @Test
    @DisplayName("counts spraying across different endpoints together")
    void limitIsPerAddressNotPerEndpoint() throws Exception {
      // The point of limiting at the gateway: per-account limits do not see a spray that changes
      // account, or endpoint, on every request.
      var filter = new AuthRateLimitFilter(new MutableClock(), 2, false);
      call(filter, "POST", "/api/v1/auth/login", "10.0.0.1", null);
      call(filter, "POST", "/api/v1/auth/signup", "10.0.0.1", null);

      assertThat(call(filter, "POST", "/api/v1/auth/forgot-password", "10.0.0.1", null).getStatus())
          .isEqualTo(429);
    }

    @Test
    @DisplayName("keeps separate clients separate")
    void separateAddressesHaveSeparateBudgets() throws Exception {
      var filter = new AuthRateLimitFilter(new MutableClock(), 1, false);
      call(filter, "POST", "/api/v1/auth/login", "10.0.0.1", null);

      assertThat(call(filter, "POST", "/api/v1/auth/login", "10.0.0.2", null).getStatus())
          .isEqualTo(200);
    }

    @Test
    @DisplayName("resets in the next minute")
    void resetsNextWindow() throws Exception {
      var clock = new MutableClock();
      var filter = new AuthRateLimitFilter(clock, 1, false);
      call(filter, "POST", "/api/v1/auth/login", "10.0.0.1", null);
      assertThat(call(filter, "POST", "/api/v1/auth/login", "10.0.0.1", null).getStatus())
          .isEqualTo(429);

      clock.now.set(clock.now.get().plusSeconds(60));
      assertThat(call(filter, "POST", "/api/v1/auth/login", "10.0.0.1", null).getStatus())
          .isEqualTo(200);
    }

    @Test
    @DisplayName("ignores X-Forwarded-For unless told to trust it")
    void forwardedForIsNotTrustedByDefault() throws Exception {
      // Otherwise a client sets the header to a fresh value on every request and is never limited.
      var filter = new AuthRateLimitFilter(new MutableClock(), 1, false);
      call(filter, "POST", "/api/v1/auth/login", "10.0.0.1", "1.1.1.1");

      assertThat(call(filter, "POST", "/api/v1/auth/login", "10.0.0.1", "2.2.2.2").getStatus())
          .isEqualTo(429);
    }

    @Test
    @DisplayName("uses the original client from X-Forwarded-For when configured to")
    void forwardedForWhenTrusted() throws Exception {
      var filter = new AuthRateLimitFilter(new MutableClock(), 1, true);
      call(filter, "POST", "/api/v1/auth/login", "10.0.0.9", "1.1.1.1, 10.0.0.9");

      // Same proxy address, different real client: a separate budget.
      assertThat(call(filter, "POST", "/api/v1/auth/login", "10.0.0.9", "2.2.2.2").getStatus())
          .isEqualTo(200);
    }

    @Test
    @DisplayName("leaves authenticated and read traffic alone")
    void onlyUnauthenticatedAuthPostsAreLimited() throws Exception {
      var filter = new AuthRateLimitFilter(new MutableClock(), 1, false);
      for (int i = 0; i < 5; i++) {
        assertThat(call(filter, "GET", "/api/v1/auth/me", "10.0.0.1", null).getStatus())
            .isEqualTo(200);
        assertThat(call(filter, "POST", "/api/v1/organizations", "10.0.0.1", null).getStatus())
            .isEqualTo(200);
      }
    }
  }

  @Nested
  @DisplayName("SecurityHeadersFilter")
  class Headers {

    private MockHttpServletResponse headersFor(boolean secure) throws Exception {
      var request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
      request.setSecure(secure);
      var response = new MockHttpServletResponse();
      new SecurityHeadersFilter().doFilter(request, response, new MockFilterChain());
      return response;
    }

    @Test
    @DisplayName("sets the API response headers")
    void setsHeaders() throws Exception {
      var response = headersFor(false);

      assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
      assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
      assertThat(response.getHeader("Content-Security-Policy")).contains("frame-ancestors 'none'");
      assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
      assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    @DisplayName("sends HSTS only over TLS")
    void hstsOnlyWhenSecure() throws Exception {
      assertThat(headersFor(false).getHeader("Strict-Transport-Security")).isNull();
      assertThat(headersFor(true).getHeader("Strict-Transport-Security")).contains("max-age=");
    }
  }

  @Nested
  @DisplayName("AccessCookieFilter")
  class AccessCookie {

    private final AccessCookieFilter filter = new AccessCookieFilter("DEVFORGE_REFRESH_TOKEN");

    /** Runs the filter and returns what the next hop saw as Authorization, or the refusal. */
    private Object[] run(MockHttpServletRequest request) throws Exception {
      var response = new MockHttpServletResponse();
      var chain = new MockFilterChain();
      filter.doFilter(request, response, chain);
      var forwarded = chain.getRequest() == null
          ? null
          : ((jakarta.servlet.http.HttpServletRequest) chain.getRequest()).getHeader("Authorization");
      return new Object[] {response.getStatus(), forwarded};
    }

    @Test
    @DisplayName("turns the access cookie into a bearer header")
    void cookieBecomesBearer() throws Exception {
      var request = new MockHttpServletRequest("GET", "/api/v1/projects");
      request.setCookies(new jakarta.servlet.http.Cookie("DEVFORGE_ACCESS_TOKEN", "abc"));

      assertThat(run(request)).containsExactly(200, "Bearer abc");
    }

    @Test
    @DisplayName("prefers an explicit Authorization header")
    void explicitHeaderWins() throws Exception {
      var request = new MockHttpServletRequest("GET", "/api/v1/projects");
      request.setCookies(new jakarta.servlet.http.Cookie("DEVFORGE_ACCESS_TOKEN", "abc"));
      request.addHeader("Authorization", "Bearer explicit");

      assertThat(run(request)).containsExactly(200, "Bearer explicit");
    }

    @Test
    @DisplayName("refuses a cookie-carrying write without X-Requested-With")
    void csrfRefused() throws Exception {
      // What a forged cross-site form post looks like: the browser attaches the cookie, but
      // another origin cannot add the header.
      var request = new MockHttpServletRequest("POST", "/api/v1/projects");
      request.setCookies(new jakarta.servlet.http.Cookie("DEVFORGE_ACCESS_TOKEN", "abc"));

      assertThat(run(request)).containsExactly(403, null);
    }

    @Test
    @DisplayName("treats the refresh cookie the same, so refresh cannot be forged")
    void refreshCsrfRefused() throws Exception {
      var request = new MockHttpServletRequest("POST", "/api/v1/auth/refresh");
      request.setCookies(new jakarta.servlet.http.Cookie("DEVFORGE_REFRESH_TOKEN", "r"));

      assertThat(run(request)).containsExactly(403, null);
    }

    @Test
    @DisplayName("lets the SPA's writes through")
    void headerPresentAllowed() throws Exception {
      var request = new MockHttpServletRequest("POST", "/api/v1/projects");
      request.setCookies(new jakarta.servlet.http.Cookie("DEVFORGE_ACCESS_TOKEN", "abc"));
      request.addHeader("X-Requested-With", "XMLHttpRequest");

      assertThat(run(request)).containsExactly(200, "Bearer abc");
    }

    @Test
    @DisplayName("leaves cookieless clients alone")
    void noCookieNoCheck() throws Exception {
      var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");

      assertThat(run(request)).containsExactly(200, null);
    }
  }
}
