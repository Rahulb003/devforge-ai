package com.devforge.ai.gateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lets the browser authenticate with an HttpOnly cookie while every service still sees a bearer
 * token.
 *
 * <p>The access token used to live in localStorage, where any injected script could read it and
 * send it anywhere. auth-service now sets it as an HttpOnly cookie, and this filter copies it into
 * the Authorization header. Doing it here, once, leaves every service and every service-to-service
 * call that forwards the caller's token unchanged.
 *
 * <p>A cookie is sent by the browser on its own, which is what makes cross-site request forgery
 * possible, so cookie-carrying writes must also carry {@code X-Requested-With}. Another site cannot
 * add that header: a form cannot set headers at all, and a cross-origin fetch that sets one needs a
 * CORS preflight this origin refuses. SameSite=Strict on the cookies is the first layer; this is the
 * second, for browsers or proxies that do not honour it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AccessCookieFilter extends OncePerRequestFilter {

  static final String ACCESS_COOKIE = "DEVFORGE_ACCESS_TOKEN";
  static final String CSRF_HEADER = "X-Requested-With";

  private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

  private final String refreshCookie;

  public AccessCookieFilter(
      @Value("${devforge.auth.refresh-cookie-name:DEVFORGE_REFRESH_TOKEN}") String refreshCookie) {
    this.refreshCookie = refreshCookie;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    String accessToken = null;
    boolean carriesAuthCookie = false;
    if (request.getCookies() != null) {
      for (Cookie cookie : request.getCookies()) {
        if (ACCESS_COOKIE.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
          accessToken = cookie.getValue();
          carriesAuthCookie = true;
        } else if (refreshCookie.equals(cookie.getName())) {
          carriesAuthCookie = true;
        }
      }
    }

    // The refresh cookie counts too: refresh is a cookie-authenticated write, and it is the one
    // that hands back a fresh token.
    if (carriesAuthCookie
        && !SAFE_METHODS.contains(request.getMethod())
        && !"XMLHttpRequest".equals(request.getHeader(CSRF_HEADER))) {
      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
      response.setContentType("application/json");
      response.getWriter().write(
          "{\"success\":false,\"message\":\"Missing X-Requested-With header.\"}");
      return;
    }

    // An explicit Authorization header wins: it is what a non-browser client sends deliberately.
    if (accessToken == null || request.getHeader(HttpHeaders.AUTHORIZATION) != null) {
      chain.doFilter(request, response);
      return;
    }
    chain.doFilter(new BearerFromCookie(request, "Bearer " + accessToken), response);
  }

  private static final class BearerFromCookie extends HttpServletRequestWrapper {
    private final String authorization;

    BearerFromCookie(HttpServletRequest request, String authorization) {
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

    @Override
    public Enumeration<String> getHeaderNames() {
      var names = new ArrayList<>(Collections.list(super.getHeaderNames()));
      names.add(HttpHeaders.AUTHORIZATION);
      return Collections.enumeration(names);
    }
  }
}
