package com.devforge.ai.gateway;

import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 * <p>Route order matters and is the subtle part. Tasks and sprints are nested <em>under</em> the
 * project path but served by task-service, so their more specific patterns must be declared before
 * the broader {@code /api/v1/organizations/**} route. Spring evaluates router functions in
 * declaration order, so a broader route declared first would swallow them and send task traffic to
 * project-service.
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

  /**
   * Tasks and sprints, which live under the project path but belong to task-service.
   *
   * <p>Declared first so the broader organizations route below cannot claim them.
   */
  @Bean
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
   * Repositories, which also live under the project path but belong to git-service.
   *
   * <p>Declared before the organizations route for the same reason as tasks: router functions are
   * matched in declaration order, so the broader pattern would otherwise swallow these.
   */
  @Bean
  public RouterFunction<ServerResponse> repositoryRoutes() {
    return route("repositories")
        .route(
            RequestPredicates.path("/api/v1/organizations/*/projects/*/repositories/**")
                .or(RequestPredicates.path("/api/v1/organizations/*/projects/*/repositories")),
            http(gitServiceUrl))
        .build();
  }

  /** Everything else under organizations: the organizations and projects themselves. */
  @Bean
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
  public RouterFunction<ServerResponse> authRoutes() {
    return route("auth")
        .route(
            RequestPredicates.path("/api/v1/auth/**").or(RequestPredicates.path("/api/v1/dev/**")),
            http(authServiceUrl))
        .build();
  }
}
