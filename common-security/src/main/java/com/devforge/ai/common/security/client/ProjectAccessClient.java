package com.devforge.ai.common.security.client;

import com.devforge.ai.common.exception.ResourceNotFoundException;
import java.time.Duration;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * Decides whether the caller may work in a project, by asking the service that owns projects.
 *
 * <p>Several services own data that hangs off a project — tasks, repositories, and in time reviews
 * and deployments — but projects and their membership live in project-service's database. Copying
 * that membership into each of them would mean several sources of truth for who can see what, and
 * a stale replica in an authorization path is a cross-tenant leak waiting to happen. Instead this
 * forwards the caller's own bearer token to project-service and lets the service that owns the
 * rule apply it. A 200 means the caller may read the project; anything else means they may not,
 * and the reason is deliberately not distinguished here.
 *
 * <p>It lives in common-security rather than in each service because it is security-critical and
 * nearly identical everywhere: duplicated, a correction would be applied to one copy and silently
 * missed in the others.
 *
 * <p>Conditional on {@code devforge.services.project-service-url}. Every service scans
 * {@code com.devforge.ai}, so without the condition this bean would also be created inside
 * project-service — which is the authority itself and has no such property — and fail its context.
 *
 * <p>The cost is a synchronous hop on the authorization path. That is accepted for now because
 * correctness matters more than latency at this stage; a short-lived per-request cache is the
 * obvious next step, and event-sourced replication only becomes safe once there is a way to know
 * the replica is not stale.
 */
@Slf4j
@Component
@ConditionalOnProperty("devforge.services.project-service-url")
public class ProjectAccessClient {

  private final RestTemplate restTemplate;
  private final String projectServiceBaseUrl;

  public ProjectAccessClient(
      RestTemplateBuilder builder,
      @Value("${devforge.services.project-service-url}") String projectServiceBaseUrl) {
    this.projectServiceBaseUrl = projectServiceBaseUrl;
    // Short timeouts: this sits in front of every task request, so a slow
    // dependency must fail fast rather than exhaust this service's threads.
    this.restTemplate = builder
        .connectTimeout(Duration.ofSeconds(2))
        .readTimeout(Duration.ofSeconds(5))
        .build();
  }

  /**
   * Asserts the caller may access the project.
   *
   * @throws ResourceNotFoundException when the project does not exist, is in another tenant, or
   *     the caller is not a member. These are deliberately indistinguishable, matching
   *     project-service: telling them apart would let a caller probe ids across tenants.
   */
  public void requireProjectAccess(UUID organizationId, UUID projectId, String bearerToken) {
    var url = "%s/api/v1/organizations/%s/projects/%s"
        .formatted(projectServiceBaseUrl, organizationId, projectId);

    var headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, bearerToken);

    try {
      restTemplate.exchange(
          url,
          org.springframework.http.HttpMethod.GET,
          new org.springframework.http.HttpEntity<>(headers),
          String.class);
    } catch (HttpClientErrorException ex) {
      HttpStatusCode status = ex.getStatusCode();
      if (status.value() == 401 || status.value() == 403 || status.value() == 404) {
        log.debug("Project access denied for project {}: {}", projectId, status);
        throw new ResourceNotFoundException("Project not found");
      }
      throw ex;
    } catch (ResourceAccessException ex) {
      // project-service unreachable. Failing closed is the only safe option: treating an
      // unavailable authority as permission would hand out access during an outage.
      log.error("project-service is unreachable; refusing access to project {}", projectId, ex);
      throw new ProjectServiceUnavailableException(
          "Cannot verify project access right now. Please try again.", ex);
    }
  }

  /** Raised when the authority for project access cannot be reached. Maps to 503. */
  public static class ProjectServiceUnavailableException extends RuntimeException {
    public ProjectServiceUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
