package com.devforge.ai.gateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses an oversized request body with 413 before it is forwarded.
 *
 * <p>Without it the gateway streamed any body to the service behind it. A service that answers
 * before reading the whole body - an endpoint that takes no body, or Tomcat giving up after its
 * 2 MB swallow limit - closes the connection mid-upload, and the gateway reported that I/O error
 * as a 500. Probing every write route with a 5.7 MB body turned up fifteen of them. nginx caps
 * bodies at 1 MB in front of the compose stack, but the gateway is reachable without it: the
 * standalone launcher, and anything inside the cluster.
 *
 * <p>Git pushes are the exception, being pack files that are often far larger; they get
 * git-service's own push limit instead.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 6)
public class RequestSizeFilter extends OncePerRequestFilter {

  private final long maxBodyBytes;
  private final long maxPushBytes;

  public RequestSizeFilter(
      @Value("${devforge.gateway.max-body-bytes:2097152}") long maxBodyBytes,
      @Value("${devforge.gateway.max-push-bytes:104857600}") long maxPushBytes) {
    this.maxBodyBytes = maxBodyBytes;
    this.maxPushBytes = maxPushBytes;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var limit = request.getRequestURI().startsWith("/api/v1/git/") ? maxPushBytes : maxBodyBytes;
    if (request.getContentLengthLong() > limit) {
      response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.getWriter().write(
          "{\"success\":false,\"message\":\"Request body too large: the limit is " + limit + " bytes\"}");
      return;
    }
    chain.doFilter(request, response);
  }
}
