package com.devforge.ai.gateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.util.Locale;
import java.util.Set;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Response headers for everything the gateway returns.
 *
 * <p>Set here, on the single browser-facing entry point, rather than per service, for the same
 * reason CORS is: one place to get right. These cover API responses only. The single-page app's own
 * HTML is not served by the gateway, so whatever serves it must set a Content-Security-Policy
 * suited to a page that runs scripts — that policy is not this one.
 */
@Component
// Before the rate limiter, so its 429 carries these headers too. The first version ran after it,
// and the refusal it writes ended the chain before any of these were set.
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class SecurityHeadersFilter extends OncePerRequestFilter {

  /**
   * Headers the downstream services also send.
   *
   * <p>The proxy copies a service's headers with addHeader, so without this each one arrived twice -
   * once from here, once from the service's Spring Security. A repeated X-Frame-Options can be
   * treated by a browser as invalid and ignored entirely. Replacing instead of appending keeps one.
   */
  private static final Set<String> SINGLE_VALUED = Set.of(
      "x-content-type-options", "x-frame-options", "cache-control", "content-security-policy",
      "referrer-policy", "strict-transport-security");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    // Stops a browser guessing a JSON body is HTML and rendering it.
    response.setHeader("X-Content-Type-Options", "nosniff");
    // An API response has no reason to be framed, load anything, or run anything.
    response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
    response.setHeader("X-Frame-Options", "DENY");
    // Paths can carry ids; they should not leak to another origin through the Referer header.
    response.setHeader("Referrer-Policy", "no-referrer");
    // Responses carry personal and tenant data. A shared or browser cache must not keep them.
    response.setHeader("Cache-Control", "no-store");

    // Only over TLS: HSTS sent on plain HTTP is ignored, and sending it from a misconfigured
    // non-TLS deployment would teach nothing except that the header exists.
    if (request.isSecure()) {
      response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
    }

    chain.doFilter(request, new HttpServletResponseWrapper(response) {
      @Override
      public void addHeader(String name, String value) {
        if (SINGLE_VALUED.contains(name.toLowerCase(Locale.ROOT))) {
          super.setHeader(name, value);
        } else {
          super.addHeader(name, value);
        }
      }
    });
  }
}
