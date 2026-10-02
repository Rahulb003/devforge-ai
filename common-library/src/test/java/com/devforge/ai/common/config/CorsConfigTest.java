package com.devforge.ai.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Guards the cross-origin policy.
 *
 * <p>This configuration previously paired {@code addAllowedOriginPattern("*")} with
 * {@code setAllowCredentials(true)}. A pattern makes Spring echo the caller's own Origin back, so
 * the browser rule that forbids a wildcard alongside credentials never fires and any site could
 * read a signed-in user's data. common-library is on every service's classpath, so the exposure
 * was platform-wide — worth a test rather than a comment.
 */
class CorsConfigTest {

  private CorsConfiguration configurationFor(String allowedOrigins) {
    var config = new CorsConfig();
    ReflectionTestUtils.setField(config, "allowedOrigins", allowedOrigins);

    var filter = config.corsFilter();
    var source = (CorsConfigurationSource) ReflectionTestUtils.getField(filter, "configSource");

    var request = new MockHttpServletRequest("GET", "/api/v1/anything");
    return source.getCorsConfiguration(request);
  }

  @Test
  @DisplayName("a wildcard origin is refused outright")
  void wildcardIsRefused() {
    // Failing to start beats silently downgrading: an operator who configured a
    // wildcard believes it is in effect.
    assertThatThrownBy(() -> configurationFor("*"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must not contain '*'");

    assertThatThrownBy(() -> configurationFor("http://localhost:4173,*"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("no origins are allowed by default")
  void deniesByDefault() {
    var config = configurationFor("");

    // Same-origin requests are unaffected; this governs only what other origins
    // may do, and "nobody" is the only safe default for a credentialed API.
    assertThat(config.getAllowedOrigins()).isEmpty();
    assertThat(config.checkOrigin("https://evil.example")).isNull();
  }

  @Test
  @DisplayName("only listed origins are allowed")
  void allowsOnlyListedOrigins() {
    var config = configurationFor("http://localhost:4173, https://app.devforge.ai");

    assertThat(config.checkOrigin("http://localhost:4173")).isEqualTo("http://localhost:4173");
    assertThat(config.checkOrigin("https://app.devforge.ai")).isEqualTo("https://app.devforge.ai");
    assertThat(config.checkOrigin("https://evil.example")).isNull();
  }

  @Test
  @DisplayName("credentials stay enabled, which is why the wildcard matters")
  void credentialsAllowed() {
    var config = configurationFor("http://localhost:4173");
    assertThat(config.getAllowCredentials()).isTrue();
  }

  @Test
  @DisplayName("allowed headers and methods are explicit rather than wildcards")
  void headersAndMethodsAreExplicit() {
    var config = configurationFor("http://localhost:4173");

    assertThat(config.getAllowedHeaders()).contains("Authorization", "Content-Type");
    assertThat(config.getAllowedHeaders()).doesNotContain("*");
    assertThat(config.getAllowedMethods()).contains("GET", "POST", "DELETE");
    assertThat(config.getAllowedMethods()).doesNotContain("*");
  }
}
