package com.devforge.ai.authservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

  private final JwtTokenProvider jwtTokenProvider;
  private final UserDetailsService userDetailsService;

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    try {
      var accessToken = getBearerToken(request);
      if (accessToken != null && jwtTokenProvider.validateToken(accessToken)) {
        var userId = jwtTokenProvider.getUserIdFromToken(accessToken);
        UserDetails userDetails = userDetailsService.loadUserByUsername(userId.toString());
        var authentication = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
      }
    } catch (Exception ex) {
      logger.debug("JWT authentication failed: {}", ex.getMessage());
    }
    filterChain.doFilter(request, response);
  }

  /**
   * Extracts the access token from the Authorization header only.
   *
   * <p>This deliberately does NOT fall back to the refresh cookie. Accepting an
   * ambient cookie as a general-purpose API credential would make every endpoint
   * authenticable without an explicit header — which, with CSRF protection disabled
   * for this stateless API, is precisely the shape CSRF exploits. The refresh cookie
   * has exactly one legitimate use, at {@code POST /api/v1/auth/refresh}, where it is
   * read explicitly and checked against the persisted refresh token.
   */
  private String getBearerToken(HttpServletRequest request) {
    var header = request.getHeader("Authorization");
    if (header != null && header.startsWith("Bearer ")) {
      return header.substring(7);
    }
    return null;
  }
}
