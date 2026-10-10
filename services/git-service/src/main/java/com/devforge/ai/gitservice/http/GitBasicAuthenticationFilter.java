package com.devforge.ai.gitservice.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lets a git client authenticate with a personal access token over HTTP Basic.
 *
 * <p>Runs before Spring Security and rewrites the request to carry the exchanged access token as a
 * bearer token, so everything downstream - the bearer filter, the project-access check - works
 * exactly as it does for the browser. Only git paths get this; the rest of the API still accepts
 * bearer tokens alone, so a personal token cannot be used there.
 */
@RequiredArgsConstructor
public class GitBasicAuthenticationFilter extends OncePerRequestFilter {

  /** What makes git prompt for credentials, or use a credential helper, instead of failing. */
  static final String CHALLENGE = "Basic realm=\"DevForge\", charset=\"UTF-8\"";

  private final PersonalTokenExchangeClient exchangeClient;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
      challenge(response);
      return;
    }

    var personalToken = passwordFrom(header.substring(6).trim());
    if (personalToken == null) {
      challenge(response);
      return;
    }

    String accessToken;
    try {
      accessToken = exchangeClient.exchange(personalToken).orElse(null);
    } catch (ResourceAccessException ex) {
      response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
          "Sign-in is unavailable right now; try again shortly");
      return;
    }
    if (accessToken == null) {
      challenge(response);
      return;
    }
    chain.doFilter(new BearerRequest(request, "Bearer " + accessToken), response);
  }

  /**
   * The token from {@code user:token}. The username is ignored, as GitHub does: the token alone
   * identifies the account, and git always sends some username.
   */
  static String passwordFrom(String encoded) {
    try {
      var decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
      var colon = decoded.indexOf(':');
      var password = colon < 0 ? "" : decoded.substring(colon + 1);
      return password.isEmpty() ? null : password;
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private static void challenge(HttpServletResponse response) throws IOException {
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE);
    response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
        "Use a personal access token from your DevForge settings as the password");
  }

  /** The original request with its Authorization header replaced. */
  private static final class BearerRequest extends HttpServletRequestWrapper {
    private final String authorization;

    BearerRequest(HttpServletRequest request, String authorization) {
      super(request);
      this.authorization = authorization;
    }

    @Override
    public String getHeader(String name) {
      return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name) ? authorization : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
      return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)
          ? Collections.enumeration(java.util.List.of(authorization))
          : super.getHeaders(name);
    }
  }
}
