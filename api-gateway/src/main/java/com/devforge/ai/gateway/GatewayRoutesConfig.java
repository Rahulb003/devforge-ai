package com.devforge.ai.gateway;

import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * The single entry point in front of the platform.
 *
 * <p>Until now the frontend proxied to three services by path prefix, which works only because
 * Vite's dev server does the routing. Nothing deployed has a dev server, so the same mapping has
 * to live somewhere real — here.
 *
 * <p>Route order matters and is the subtle part. Several services own paths nested inside another
 * service's: tasks and repositories sit under the project path, and reviews sit under a
 * repository's. A broader pattern matched first would swallow the narrower one and send the
 * traffic to a service with no such endpoint.
 *
 * <p>Precedence is therefore declared with {@link Order}, most specific first, rather than left to
 * method declaration order. Spring sorts these beans with AnnotationAwareOrderComparator, so
 * {@code @Order} is deterministic; declaration order happens to work but is not guaranteed, and
 * reordering two methods during an unrelated edit would silently reroute traffic.
 */
@Configuration
public class GatewayRoutesConfig {

  @Value("${devforge.services.auth-service-url}")
  private String authServiceUrl;

  @Value("${devforge.services.project-service-url}")
  private String projectServiceUrl;

  @Value("${devforge.services.task-service-url}")
  private String taskServiceUrl;

  @Value("${devforge.services.notification-service-url}")
  private String notificationServiceUrl;

  @Value("${devforge.services.git-service-url}")
  private String gitServiceUrl;

  @Value("${devforge.services.review-service-url}")
  private String reviewServiceUrl;

  @Value("${devforge.services.documentation-service-url}")
  private String documentationServiceUrl;

  @Value("${devforge.services.analytics-service-url}")
  private String analyticsServiceUrl;

  /**
   * Tasks and sprints, which live under the project path but belong to task-service.
   *
   * <p>Declared first so the broader organizations route below cannot claim them.
   */
  @Bean
  @Order(10)
  public RouterFunction<ServerResponse> taskRoutes() {
    return route("tasks")
        .route(
            RequestPredicates.path("/api/v1/organizations/*/projects/*/tasks/**")
                .or(RequestPredicates.path("/api/v1/organizations/*/projects/*/tasks"))
                .or(RequestPredicates.path("/api/v1/organizations/*/projects/*/sprints/**"))
                .or(RequestPredicates.path("/api/v1/organizations/*/projects/*/sprints")),
            http(taskServiceUrl))
        .build();
  }

  /**
   * Reviews, which are addressed under a repository but belong to review-service.
   *
   * <p>Declared before the repositories route, not after: the repository pattern ends in
   * {@code /repositories/**}, which also matches {@code .../repositories/{id}/reviews}. Router
   * functions match in declaration order, so the broader one would swallow every review
   * request and send it to git-service, which has no such endpoint.
   */
  @Bean
  @Order(20)
  public RouterFunction<ServerResponse> reviewRoutes() {
    return route("reviews")
        .route(
            RequestPredicates.path("/api/v1/organizations/*/projects/*/repositories/*/reviews")
                .or(RequestPredicates.path(
                    "/api/v1/organizations/*/projects/*/repositories/*/reviews/**")),
            http(reviewServiceUrl))
        .build();
  }

  /**
   * Generated documentation, nested under a repository like reviews.
   *
   * <p>Before the repositories route for the same reason: {@code /repositories/**} also matches
   * every path beneath a repository.
   */
  @Bean
  @Order(25)
  public RouterFunction<ServerResponse> documentationRoutes() {
    return route("docs")
        .route(
            RequestPredicates.path("/api/v1/organizations/*/projects/*/repositories/*/docs")
                .or(RequestPredicates.path(
                    "/api/v1/organizations/*/projects/*/repositories/*/docs/**")),
            http(documentationServiceUrl))
        .build();
  }

  /**
   * Repositories, which also live under the project path but belong to git-service.
   *
   * <p>Declared before the organizations route for the same reason as tasks: router functions are
   * matched in declaration order, so the broader pattern would otherwise swallow these.
   */
  @Bean
  @Order(30)
  public RouterFunction<ServerResponse> repositoryRoutes() {
    return route("repositories")
        .route(
            RequestPredicates.path("/api/v1/organizations/*/projects/*/repositories/**")
                .or(RequestPredicates.path("/api/v1/organizations/*/projects/*/repositories")),
            http(gitServiceUrl))
        .build();
  }

  /**
   * Project analytics, which sit under the project path like tasks.
   *
   * <p>Before the organizations route for the usual reason: the broader pattern also matches it.
   */
  @Bean
  @Order(35)
  public RouterFunction<ServerResponse> analyticsRoutes() {
    return route("analytics")
        .route(
            RequestPredicates.path("/api/v1/organizations/*/projects/*/analytics")
                .or(RequestPredicates.path("/api/v1/organizations/*/projects/*/analytics/**")),
            http(analyticsServiceUrl))
        .build();
  }

  /** Everything else under organizations: the organizations and projects themselves. */
  @Bean
  @Order(40)
  public RouterFunction<ServerResponse> projectRoutes() {
    return route("projects")
        .route(RequestPredicates.path("/api/v1/organizations/**"), http(projectServiceUrl))
        .build();
  }

  /**
   * The caller's own notifications.
   *
   * <p>Not nested under an organization, unlike tasks and projects. A notification can concern the
   * account itself — a password change, a disabled second factor — which belongs to no tenant, so
   * forcing these under an organization path would mean inventing one.
   */
  @Bean
  @Order(50)
  public RouterFunction<ServerResponse> notificationRoutes() {
    return route("notifications")
        .route(
            RequestPredicates.path("/api/v1/notifications")
                .or(RequestPredicates.path("/api/v1/notifications/**")),
            http(notificationServiceUrl))
        .build();
  }

  /**
   * Authentication, plus the development mailbox.
   *
   * <p>The mailbox only exists when the backing service runs with the development mail provider;
   * routing to it here is harmless otherwise, because there is no handler to reach.
   */
  @Bean
  @Order(60)
  public RouterFunction<ServerResponse> authRoutes() {
    return route("auth")
        .route(
            RequestPredicates.path("/api/v1/auth/**").or(RequestPredicates.path("/api/v1/dev/**")),
            http(authServiceUrl))
        .build();
  }
}
