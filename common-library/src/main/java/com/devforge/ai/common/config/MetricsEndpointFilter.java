package com.devforge.ai.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards the Prometheus scrape endpoint with its own credential.
 *
 * <p>Prometheus cannot present a user's token, so the endpoint is open at the Spring Security
 * layer and checked here instead: HTTP basic auth as {@code metrics} with the token in
 * {@code devforge.metrics.token}. Basic, not Bearer, because the bearer filter would try to read a
 * Bearer value as a JWT and reject it before this ran.
 *
 * <p>Fails closed. With no token configured the endpoint answers 404 to everyone - metrics name
 * every route and show traffic and errors, which is not for anyone who can reach a port.
 */
@Component
public class MetricsEndpointFilter extends OncePerRequestFilter {

  static final String PATH = "/actuator/prometheus";
  static final String USERNAME = "metrics";

  private final byte[] expected;

  public MetricsEndpointFilter(@Value("${devforge.metrics.token:}") String token) {
    this.expected = token == null || token.isBlank()
        ? null
        : (USERNAME + ":" + token).getBytes(StandardCharsets.UTF_8);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !PATH.equals(request.getRequestURI());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (expected == null) {
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
      return;
    }
    var header = request.getHeader("Authorization");
    if (header == null || !header.startsWith("Basic ")) {
      response.setHeader("WWW-Authenticate", "Basic realm=\"metrics\"");
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }
    byte[] presented;
    try {
      presented = Base64.getDecoder().decode(header.substring(6).trim());
    } catch (IllegalArgumentException ex) {
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }
    // Constant time, so the comparison does not leak how much of the token matched.
    if (!MessageDigest.isEqual(presented, expected)) {
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }
    chain.doFilter(request, response);
  }
}
