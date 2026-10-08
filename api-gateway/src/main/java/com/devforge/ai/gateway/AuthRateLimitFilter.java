package com.devforge.ai.gateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-client-address limit on the unauthenticated auth endpoints.
 *
 * <p>auth-service already limits attempts per <em>account</em>. That stops guessing one password,
 * but not spraying one password across thousands of accounts, nor a signup or reset-email flood —
 * each of those touches a different account every time. Limiting by source address at the gateway
 * is the control that covers them.
 *
 * <p>Fixed one-minute windows, in memory. Deliberately simple: it is a blunt instrument against
 * floods, not a fair-share scheduler. Two limitations are stated rather than hidden:
 *
 * <ul>
 *   <li>With several gateway instances, each counts separately, so the effective limit is
 *       multiplied by the instance count. A shared store (Redis) fixes that.
 *   <li>The client address is the TCP peer. Behind a load balancer every request would share the
 *       balancer's address and the limit would throttle everyone together. {@code X-Forwarded-For}
 *       is <em>not</em> read by default, because a client can set it to anything and would then
 *       choose its own bucket; trust it only behind a proxy that overwrites it.
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthRateLimitFilter extends OncePerRequestFilter {

  /** Endpoints reachable without a token, where abuse costs the caller nothing. */
  private static final Set<String> LIMITED = Set.of(
      "/api/v1/auth/login",
      "/api/v1/auth/login/mfa",
      "/api/v1/auth/signup",
      "/api/v1/auth/forgot-password",
      "/api/v1/auth/reset-password",
      "/api/v1/auth/resend-verification");

  private final Map<String, Window> windows = new ConcurrentHashMap<>();
  private final Clock clock;
  private final int perMinute;
  private final boolean trustForwardedFor;

  // Marked explicitly: with a second, test-only constructor Spring will not pick one by itself.
  @Autowired
  public AuthRateLimitFilter(
      @Value("${devforge.rate-limit.auth-per-minute:30}") int perMinute,
      @Value("${devforge.rate-limit.trust-forwarded-for:false}") boolean trustForwardedFor) {
    this(Clock.systemUTC(), perMinute, trustForwardedFor);
  }

  AuthRateLimitFilter(Clock clock, int perMinute, boolean trustForwardedFor) {
    this.clock = clock;
    this.perMinute = perMinute;
    this.trustForwardedFor = trustForwardedFor;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !"POST".equals(request.getMethod()) || !LIMITED.contains(request.getRequestURI());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    var minute = clock.millis() / 60_000;
    var window = windows.compute(clientAddress(request), (key, existing) ->
        existing == null || existing.minute != minute ? new Window(minute) : existing);

    if (window.count.incrementAndGet() > perMinute) {
      var retryAfter = 60 - (clock.millis() / 1000) % 60;
      response.setStatus(429);
      response.setHeader("Retry-After", Long.toString(retryAfter));
      response.setContentType("application/json");
      response.getWriter().write(
          "{\"success\":false,\"message\":\"Too many attempts. Try again in a minute.\"}");
      return;
    }

    // Old windows are dropped opportunistically, so a scan from many addresses cannot grow the map
    // without bound.
    if (windows.size() > 10_000) {
      windows.values().removeIf(w -> w.minute != minute);
    }
    chain.doFilter(request, response);
  }

  private String clientAddress(HttpServletRequest request) {
    if (trustForwardedFor) {
      var forwarded = request.getHeader("X-Forwarded-For");
      if (forwarded != null && !forwarded.isBlank()) {
        // The left-most entry is the original client, as written by the first trusted proxy.
        return forwarded.split(",")[0].strip();
      }
    }
    return request.getRemoteAddr();
  }

  private static final class Window {
    final long minute;
    final AtomicInteger count = new AtomicInteger();

    Window(long minute) {
      this.minute = minute;
    }
  }
}
