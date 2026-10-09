package com.devforge.ai.common.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Default filter chain for a DevForge resource server.
 *
 * <p>Every request must carry a valid access token except the actuator probes and the error
 * dispatch. {@code @EnableMethodSecurity} turns on {@code @PreAuthorize}, which is where the
 * per-tenant authorization decisions actually live — a URL-level rule cannot express "the caller
 * is a member of the organization owning this project".
 */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class ResourceServerSecurityConfig {

  private final JwtTokenVerifier tokenVerifier;

  @Bean
  public SecurityFilterChain resourceServerFilterChain(HttpSecurity http) throws Exception {
    http
        // Stateless bearer-token API: there is no session cookie for an attacker to ride,
        // and no CSRF-able ambient credential, because the filter ignores cookies entirely.
        .csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(authorize -> authorize
            .requestMatchers("/actuator/health/**", "/actuator/info", "/error").permitAll()
            // Its own credential, checked by MetricsEndpointFilter: Prometheus has no user token.
            .requestMatchers("/actuator/prometheus").permitAll()
            .anyRequest().authenticated())
        // Return 401 rather than redirecting to a login form: this is an API, and a 302 to
        // a nonexistent page turns an auth failure into a confusing client error.
        .exceptionHandling(ex -> ex.authenticationEntryPoint(
            new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
        .addFilterBefore(
            new BearerTokenAuthenticationFilter(tokenVerifier),
            UsernamePasswordAuthenticationFilter.class);

    return http.build();
  }
}
