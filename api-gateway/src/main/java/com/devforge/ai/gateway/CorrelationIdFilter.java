package com.devforge.ai.gateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Attaches a correlation id to every request entering the platform.
 *
 * <p>One request fans out across several services, and without a shared id their logs cannot be
 * stitched back together — the most common reason an incident takes hours instead of minutes. The
 * id is put in the MDC so it appears on every log line, echoed to the client so a user can quote
 * it in a bug report, and forwarded downstream on a header.
 *
 * <p>An inbound id is reused so a caller that already has one (another service, or a browser
 * retrying) keeps the same trace. It is validated rather than trusted: the value reaches log
 * files, and an unbounded caller-controlled string is how log injection and unbounded log growth
 * happen.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Correlation-Id";
  public static final String MDC_KEY = "correlationId";

  /** Conservative: letters, digits, hyphen and underscore, bounded length. */
  private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    var correlationId = sanitise(request.getHeader(HEADER));

    MDC.put(MDC_KEY, correlationId);
    response.setHeader(HEADER, correlationId);

    try {
      filterChain.doFilter(request, response);
    } finally {
      // Threads are pooled, so a stale id would leak into an unrelated request.
      MDC.remove(MDC_KEY);
    }
  }

  private String sanitise(String inbound) {
    if (inbound != null && SAFE_ID.matcher(inbound).matches()) {
      return inbound;
    }
    return UUID.randomUUID().toString();
  }
}
