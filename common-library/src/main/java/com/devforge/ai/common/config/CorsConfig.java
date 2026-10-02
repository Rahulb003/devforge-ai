package com.devforge.ai.common.config;

import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Cross-origin policy.
 *
 * <p>This previously combined {@code addAllowedOriginPattern("*")} with
 * {@code setAllowCredentials(true)}. That is not the harmless-looking wildcard it appears to be:
 * unlike {@code allowedOrigins}, a <em>pattern</em> makes Spring echo the caller's actual
 * {@code Origin} back in {@code Access-Control-Allow-Origin}. The browser rule that forbids
 * {@code *} alongside credentials is therefore never triggered, and <em>any</em> website could
 * issue credentialed requests to the API and read the responses of a signed-in user. Since
 * common-library is on every service's classpath, every service was affected.
 *
 * <p>Origins are now an explicit allow-list from configuration, and a wildcard is refused
 * outright while credentials are enabled.
 */
@Slf4j
@Configuration
public class CorsConfig {

  /**
   * Comma-separated origins permitted to make credentialed requests.
   *
   * <p>Empty by default. Same-origin requests are unaffected; this only governs what other
   * origins may do, and defaulting to "nobody" is the only safe default for a credentialed API.
   */
  @Value("${devforge.cors.allowed-origins:}")
  private String allowedOrigins;

  @Bean
  public CorsFilter corsFilter() {
    var origins = Arrays.stream(allowedOrigins.split(","))
        .map(String::trim)
        .filter(origin -> !origin.isEmpty())
        .toList();

    if (origins.contains("*")) {
      // Failing to start is deliberate. Silently downgrading to a safe value would hide a
      // misconfiguration that the operator believes is in effect.
      throw new IllegalStateException(
          "devforge.cors.allowed-origins must not contain '*'. This API allows credentials, and a "
              + "wildcard origin would let any site read a signed-in user's data. List the exact "
              + "origins instead.");
    }

    var config = new CorsConfiguration();
    config.setAllowedOrigins(origins);
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-Id"));
    config.setExposedHeaders(List.of("X-Correlation-Id"));
    config.setAllowCredentials(true);
    config.setMaxAge(3600L);

    if (origins.isEmpty()) {
      log.info("CORS: no cross-origin origins allowed. Set devforge.cors.allowed-origins to permit some.");
    } else {
      log.info("CORS: allowing credentialed requests from {}", origins);
    }

    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return new CorsFilter(source);
  }
}
