package com.devforge.ai.authservice.config;

import com.devforge.ai.authservice.security.JwtAuthenticationFilter;
import com.devforge.ai.authservice.security.CustomOAuth2UserService;
import com.devforge.ai.authservice.security.JwtTokenProvider;
import com.devforge.ai.authservice.service.CustomUserDetailsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Slf4j
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

  private final JwtTokenProvider jwtTokenProvider;
  private final CustomUserDetailsService userDetailsService;
  private final CustomOAuth2UserService oAuth2UserService;

  /**
   * OAuth2 login is only wired in when at least one client registration (Google, Microsoft, ...)
   * is actually configured.
   *
   * <p>{@code oauth2Login()} hard-requires a {@link ClientRegistrationRepository} bean, which
   * Spring Boot only creates when {@code spring.security.oauth2.client.registration.*} is
   * populated. Calling it unconditionally means the whole service refuses to start in any
   * environment without OAuth credentials — including local development and CI. Password login
   * must keep working on its own, so the OAuth block is applied conditionally.
   */
  @Bean
  public SecurityFilterChain filterChain(
      HttpSecurity http, ObjectProvider<ClientRegistrationRepository> clientRegistrationRepository)
      throws Exception {
    http
        // The API is stateless and authenticated by bearer token rather than a session cookie,
        // so there is no CSRF-able ambient credential on these endpoints. The refresh cookie is
        // SameSite-constrained instead; see JwtTokenProvider.
        .csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(authorize -> authorize
            // Only the endpoints that establish a session are public. A blanket
            // /api/v1/auth/** permitAll would expose MFA management and session
            // revocation to anonymous callers — anyone could disable a user's second
            // factor or sign them out.
            .requestMatchers(
                "/api/v1/auth/signup",
                "/api/v1/auth/login",
                "/api/v1/auth/login/mfa",
                "/api/v1/auth/refresh",
                "/api/v1/auth/forgot-password",
                "/api/v1/auth/reset-password",
                "/api/v1/auth/verify-email",
                "/api/v1/auth/resend-verification",
                "/api/v1/auth/oauth2/**").permitAll()
            // Development mailbox. Unauthenticated by necessity — it exists to
            // complete a signup before there is an account to sign in with. The
            // controller itself only exists when
            // devforge.mail.dev-mailbox-enabled=true, so this rule matches nothing
            // in any deployment that sends real mail.
            .requestMatchers("/api/v1/dev/mailbox").permitAll()
            // Authenticated by the personal token in its body. Internal: the gateway does not route
            // /internal, so only other services reach it. See TokenExchangeController.
            .requestMatchers(org.springframework.http.HttpMethod.POST, "/internal/v1/tokens/exchange")
                .permitAll()
            .requestMatchers("/actuator/health/**", "/actuator/info", "/error").permitAll()
            // Its own credential, checked by MetricsEndpointFilter: Prometheus has no user token.
            .requestMatchers("/actuator/prometheus").permitAll()
            .anyRequest().authenticated())
        // Without an explicit entry point, an unauthenticated call to a protected endpoint
        // returns 403, which tells the client "you are known but not allowed" when the truth
        // is "you are not signed in". 401 is the correct signal and is what drives a client
        // to refresh its token.
        .exceptionHandling(ex -> ex.authenticationEntryPoint(
            new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
        .addFilterBefore(jwtAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);

    if (clientRegistrationRepository.getIfAvailable() != null) {
      http.oauth2Login(oauth2 -> oauth2
          .authorizationEndpoint(endpoint -> endpoint.baseUri("/api/v1/auth/oauth2/authorize"))
          .redirectionEndpoint(endpoint -> endpoint.baseUri("/api/v1/auth/oauth2/callback/*"))
          .userInfoEndpoint(userInfo -> userInfo.userService(oAuth2UserService))
          .successHandler((request, response, authentication) ->
              jwtTokenProvider.handleOAuthSuccess(response, authentication)));
      log.info("OAuth2 login enabled: client registrations found.");
    } else {
      log.info("OAuth2 login disabled: no spring.security.oauth2.client.registration.* configured.");
    }

    return http.build();
  }

  @Bean
  public JwtAuthenticationFilter jwtAuthenticationFilter() {
    return new JwtAuthenticationFilter(jwtTokenProvider, userDetailsService);
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfiguration) throws Exception {
    return authConfiguration.getAuthenticationManager();
  }
}
