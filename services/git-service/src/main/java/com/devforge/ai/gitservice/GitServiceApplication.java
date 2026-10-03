package com.devforge.ai.gitservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Entity and repository scanning is widened explicitly.
 *
 * <p>scanBasePackages only affects component scanning. JPA entity scanning defaults to this class's
 * own package, so the shared outbox and processed-event entities in com.devforge.ai.common.events
 * would be invisible and their tables never mapped.
 */
@SpringBootApplication(scanBasePackages = "com.devforge.ai")
@EntityScan({"com.devforge.ai.gitservice.entity", "com.devforge.ai.common"})
@EnableJpaRepositories({"com.devforge.ai.gitservice.repository", "com.devforge.ai.common"})
public class GitServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(GitServiceApplication.class, args);
  }
}
