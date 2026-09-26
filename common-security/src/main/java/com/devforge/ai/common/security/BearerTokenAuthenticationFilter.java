package com.devforge.ai.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Populates the security context from a verified {@code Authorization: Bearer} token.
 *
 * <p>Only the Authorization header is read. Cookies are never accepted as an API credential:
 * an ambient credential that the browser attaches automatically is the ingredient CSRF needs,
 * and these APIs are stateless with CSRF protection disabled.
 */
@RequiredArgsConstructor
public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

  private static final String BEARER_PREFIX = "Bearer ";

  private final JwtTokenVerifier tokenVerifier;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    var token = bearerToken(request);
    if (token != null) {
      tokenVerifier.verify(token).ifPresent(user -> {
        var authorities = user.roles().stream()
            .map(SimpleGrantedAuthority::new)
            .toList();
        var authentication =
            new UsernamePasswordAuthenticationToken(user, null, List.copyOf(authorities));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
      });
    }

    filterChain.doFilter(request, response);
  }

  private String bearerToken(HttpServletRequest request) {
    var header = request.getHeader("Authorization");
    if (header != null && header.startsWith(BEARER_PREFIX)) {
      return header.substring(BEARER_PREFIX.length());
    }
    return null;
  }
}
