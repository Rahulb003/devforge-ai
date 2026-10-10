package com.devforge.ai.aiservice.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * Calls Claude through Anthropic's Messages API.
 *
 * <p>Without an API key the client reports itself unconfigured and never calls anything: there is
 * no stand-in model. With one, every failure is surfaced as a {@link ModelUnavailableException}
 * carrying a message fit for the user, and never as text that could be mistaken for an answer.
 */
@Slf4j
@Component
public class ModelClient {

  private final RestTemplate restTemplate;
  private final String apiKey;
  private final String baseUrl;
  private final String model;
  private final int maxOutputTokens;

  public ModelClient(
      RestTemplateBuilder builder,
      @Value("${devforge.ai.api-key:}") String apiKey,
      @Value("${devforge.ai.base-url:https://api.anthropic.com}") String baseUrl,
      @Value("${devforge.ai.model:claude-opus-5-5}") String model,
      @Value("${devforge.ai.max-output-tokens:1500}") int maxOutputTokens) {
    this.apiKey = apiKey == null ? "" : apiKey.trim();
    this.baseUrl = baseUrl;
    this.model = model;
    this.maxOutputTokens = maxOutputTokens;
    this.restTemplate = builder
        .connectTimeout(Duration.ofSeconds(5))
        // A long answer takes a while to generate; a stuck connection must still end.
        .readTimeout(Duration.ofSeconds(90))
        .build();
  }

  public boolean isConfigured() {
    return !apiKey.isEmpty();
  }

  public String model() {
    return model;
  }

  /** One system prompt and one user turn; the reply's text. */
  public String complete(String system, String user) {
    if (!isConfigured()) {
      throw new ModelUnavailableException("AI assistance is not configured on this deployment");
    }
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("x-api-key", apiKey);
    headers.set("anthropic-version", "2023-06-01");
    var body = Map.of(
        "model", model,
        "max_tokens", maxOutputTokens,
        "system", system,
        "messages", List.of(Map.of("role", "user", "content", user)));
    try {
      var response = restTemplate.postForObject(
          baseUrl + "/v1/messages", new HttpEntity<>(body, headers), JsonNode.class);
      var text = new StringBuilder();
      if (response != null) {
        for (var block : response.path("content")) {
          if ("text".equals(block.path("type").asText())) {
            text.append(block.path("text").asText());
          }
        }
      }
      if (text.isEmpty()) {
        throw new ModelUnavailableException("The model returned no answer; try again");
      }
      return text.toString();
    } catch (HttpStatusCodeException ex) {
      var status = ex.getStatusCode().value();
      // The key and the provider's error body stay in the logs, never in the response.
      log.warn("Model API answered {}: {}", status, ex.getResponseBodyAsString());
      if (status == 401 || status == 403) {
        throw new ModelUnavailableException("AI assistance is misconfigured on this deployment");
      }
      if (status == 429 || status == 529) {
        throw new ModelUnavailableException("The AI provider is busy; try again in a minute");
      }
      throw new ModelUnavailableException("The AI provider could not answer; try again");
    } catch (ResourceAccessException ex) {
      log.warn("Model API unreachable", ex);
      throw new ModelUnavailableException("The AI provider could not be reached; try again");
    }
  }

  public static class ModelUnavailableException extends RuntimeException {
    public ModelUnavailableException(String message) {
      super(message);
    }
  }
}
