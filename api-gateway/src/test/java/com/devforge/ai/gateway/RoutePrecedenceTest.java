package com.devforge.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Which service a path is routed to, and in what order the routes are tried.
 *
 * <p>This is the test the gateway was missing. Several services own paths nested inside another
 * service's — tasks and repositories under the project path, reviews under a repository's — so the
 * broader pattern must never be tried first. Nothing asserted that, and the only symptom of getting
 * it wrong would be a 404 from a service that has no such endpoint.
 *
 * <p>Routes are matched here rather than requested over HTTP: the test profile points every
 * upstream at a dead port, so an actual request fails identically whichever route won.
 */
@SpringBootTest
@DisplayName("Gateway route precedence")
class RoutePrecedenceTest {

  @Autowired private List<RouterFunction<ServerResponse>> routerFunctions;

  /**
   * The name of the first route that claims a path.
   *
   * <p>Beans are sorted exactly as Spring's {@code RouterFunctionMapping} sorts them, so this
   * reflects the real precedence rather than whatever order injection happened to produce.
   */
  private String routeFor(String path) {
    var ordered = new java.util.ArrayList<>(routerFunctions);
    AnnotationAwareOrderComparator.sort(ordered);

    var request = ServerRequest.create(
        new MockHttpServletRequest("GET", path), List.of());

    for (var router : ordered) {
      var match = router.route(request);
      if (match.isPresent()) {
        // Each route is declared with route("<name>"), which Spring Cloud Gateway MVC records as
        // the router function's string form.
        return router.toString();
      }
    }
    return "(unrouted)";
  }

  @Test
  @DisplayName("tasks and sprints go to task-service, not project-service")
  void tasksBeatProjects() {
    var organization = "/api/v1/organizations/11111111-1111-1111-1111-111111111111";
    var project = organization + "/projects/22222222-2222-2222-2222-222222222222";

    assertThat(routeFor(project + "/tasks")).contains("tasks");
    assertThat(routeFor(project + "/tasks/33333333-3333-3333-3333-333333333333")).contains("tasks");
    assertThat(routeFor(project + "/tasks/board")).contains("tasks");
    assertThat(routeFor(project + "/sprints")).contains("tasks");

    // Analytics nests under a project the same way.
    assertThat(routeFor(project + "/analytics")).contains("analytics");

    // The broader route still claims everything else under organizations.
    assertThat(routeFor(organization)).contains("projects");
    assertThat(routeFor(organization + "/projects")).contains("projects");
    assertThat(routeFor(project)).contains("projects");
  }

  @Test
  @DisplayName("reviews go to review-service, not git-service")
  void reviewsBeatRepositories() {
    var project = "/api/v1/organizations/11111111-1111-1111-1111-111111111111"
        + "/projects/22222222-2222-2222-2222-222222222222";
    var repository = project + "/repositories/44444444-4444-4444-4444-444444444444";

    // This is the pairing most easily broken: the repository pattern ends in /repositories/**,
    // which also matches every review path beneath it.
    assertThat(routeFor(repository + "/reviews")).contains("reviews");
    assertThat(routeFor(repository + "/reviews/latest")).contains("reviews");
    assertThat(routeFor(repository + "/reviews/55555555-5555-5555-5555-555555555555/findings"))
        .contains("reviews");

    // Generated documentation nests the same way and must not be claimed by git-service either.
    assertThat(routeFor(repository + "/docs")).contains("docs");
    assertThat(routeFor(repository + "/docs/latest")).contains("docs");
    assertThat(routeFor(repository + "/docs/66666666-6666-6666-6666-666666666666/documents/OVERVIEW"))
        .contains("docs");

    // And git-service keeps the rest of the repository surface.
    assertThat(routeFor(project + "/repositories")).contains("repositories");
    assertThat(routeFor(repository)).contains("repositories");
    assertThat(routeFor(repository + "/blob")).contains("repositories");
    assertThat(routeFor(repository + "/tree")).contains("repositories");
    assertThat(routeFor(repository + "/branches")).contains("repositories");
  }

  @Test
  @DisplayName("auth and notifications are routed to their own services")
  void flatRoutes() {
    assertThat(routeFor("/api/v1/auth/login")).contains("auth");
    assertThat(routeFor("/api/v1/auth/sessions")).contains("auth");
    assertThat(routeFor("/api/v1/dev/mailbox")).contains("auth");
    assertThat(routeFor("/api/v1/notifications")).contains("notifications");
    assertThat(routeFor("/api/v1/notifications/unread-count")).contains("notifications");
  }

  @Test
  @DisplayName("an unknown path is not routed anywhere")
  void unknownPathIsUnrouted() {
    // A catch-all would turn a typo into a confusing error from an arbitrary service.
    assertThat(routeFor("/api/v1/nonexistent")).isEqualTo("(unrouted)");
    assertThat(routeFor("/api/v2/auth/login")).isEqualTo("(unrouted)");
  }
}
