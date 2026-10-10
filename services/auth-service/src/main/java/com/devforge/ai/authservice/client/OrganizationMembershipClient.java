package com.devforge.ai.authservice.client;

import com.devforge.ai.common.exception.ResourceConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Takes a user out of every organization, at project-service, before their account is deleted.
 *
 * <p>With the user's own token, so project-service applies its ordinary rule - a caller acting on
 * their own memberships - and auth-service needs no standing authority over another service's
 * data. Anything but a clear answer fails the deletion: an account deleted while its memberships
 * survive would leave an owner nobody can sign in as.
 */
@Slf4j
@Component
public class OrganizationMembershipClient {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final RestTemplate restTemplate;
  private final String baseUrl;

  public OrganizationMembershipClient(
      RestTemplateBuilder builder, @Value("${devforge.services.project-service-url:}") String baseUrl) {
    this.baseUrl = baseUrl;
    this.restTemplate = builder.connectTimeout(Duration.ofSeconds(2)).readTimeout(Duration.ofSeconds(10)).build();
  }

  /**
   * @return how many organizations the user left
   * @throws ResourceConflictException while the user is the only owner of an organization; the
   *     message, which names them, is project-service's
   * @throws DependencyUnavailableException when project-service cannot give an answer
   */
  public int leaveAllOrganizations(String bearerToken) {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new DependencyUnavailableException("Account deletion is not available on this deployment");
    }
    var headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
    try {
      var response = restTemplate.exchange(
          baseUrl + "/api/v1/memberships/mine", HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
      return JSON.readTree(response.getBody()).path("data").path("organizationsLeft").asInt();
    } catch (HttpClientErrorException.Conflict ex) {
      throw new ResourceConflictException(messageOf(ex.getResponseBodyAsString()));
    } catch (RestClientException | com.fasterxml.jackson.core.JsonProcessingException ex) {
      log.error("project-service did not remove the memberships of a user being deleted", ex);
      throw new DependencyUnavailableException("Your account cannot be deleted right now. Please try again.");
    }
  }

  private static String messageOf(String body) {
    try {
      var message = JSON.readTree(body).path("message").asText("");
      return message.isBlank() ? "You still own an organization" : message;
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      return "You still own an organization";
    }
  }

  /** A service this one depends on could not answer; reported as 503. */
  public static class DependencyUnavailableException extends RuntimeException {
    public DependencyUnavailableException(String message) {
      super(message);
    }
  }
}
