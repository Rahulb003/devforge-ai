package com.devforge.ai.gitservice.http;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * Turns a git client's personal access token into a short-lived access token, via auth-service.
 *
 * <p>The access token stays inside this service: it is what project-service is asked with, exactly
 * as for a browser request, and is never returned to the git client.
 */
@Slf4j
@Component
public class PersonalTokenExchangeClient {

  private final RestTemplate restTemplate;
  private final String exchangeUrl;

  public PersonalTokenExchangeClient(
      RestTemplateBuilder builder,
      @Value("${devforge.services.auth-service-url}") String authServiceUrl) {
    this.exchangeUrl = authServiceUrl + "/internal/v1/tokens/exchange";
    this.restTemplate = builder
        .connectTimeout(Duration.ofSeconds(2))
        .readTimeout(Duration.ofSeconds(5))
        .build();
  }

  /**
   * The access token, or empty when auth-service refuses the personal token.
   *
   * @throws org.springframework.web.client.ResourceAccessException when auth-service cannot be
   *     reached. Not mapped to "refused": a git client would then ask for a new password during
   *     an outage, and the user would assume theirs was wrong.
   */
  @SuppressWarnings("unchecked")
  public Optional<String> exchange(String personalToken) {
    try {
      var response = restTemplate.postForObject(
          exchangeUrl, Map.of("token", personalToken), Map.class);
      var accessToken = response == null ? null : response.get("accessToken");
      return accessToken instanceof String token && !token.isBlank()
          ? Optional.of(token)
          : Optional.empty();
    } catch (HttpClientErrorException ex) {
      if (ex.getStatusCode().value() != 401) {
        log.warn("Token exchange answered {}", ex.getStatusCode());
      }
      return Optional.empty();
    }
  }
}
