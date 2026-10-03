package com.devforge.ai.notificationservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Entity and repository scanning is widened explicitly.
 *
 * <p>scanBasePackages only affects component scanning. JPA entity scanning defaults to this class's
 * own package, so the shared processed-event and outbox entities in com.devforge.ai.common.events
 * would be invisible and their tables never mapped — and this service depends on the
 * processed-event table for deduplication, so it would silently duplicate every redelivered event.
 */
@SpringBootApplication(scanBasePackages = "com.devforge.ai")
@EntityScan({"com.devforge.ai.notificationservice.entity", "com.devforge.ai.common"})
@EnableJpaRepositories({"com.devforge.ai.notificationservice.repository", "com.devforge.ai.common"})
public class NotificationServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(NotificationServiceApplication.class, args);
  }
}
