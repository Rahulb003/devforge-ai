package com.devforge.ai.projectservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.devforge.ai")
// Widened over the shared outbox entities: entity scanning defaults to this package only, and a
// service that misses them cannot record events at all.
@org.springframework.boot.autoconfigure.domain.EntityScan(
    {"com.devforge.ai.projectservice.entity", "com.devforge.ai.common"})
@org.springframework.data.jpa.repository.config.EnableJpaRepositories(
    {"com.devforge.ai.projectservice.repository", "com.devforge.ai.common"})
public class ProjectServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(ProjectServiceApplication.class, args);
  }
}
