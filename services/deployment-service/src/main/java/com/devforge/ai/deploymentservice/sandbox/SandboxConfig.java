package com.devforge.ai.deploymentservice.sandbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Off unless a deployment turns it on. Giving this service a container engine is giving it the
 * power to start containers, which is close to root on the engine's host; that is a decision for
 * whoever runs the platform, made knowingly (see docs/SANDBOX.md), never a default.
 *
 * <p>Off means every run is refused (G12). There is no other way to run code here.
 */
@Slf4j
@Configuration
public class SandboxConfig {

  @Bean
  Sandbox sandbox(@Value("${devforge.sandbox.enabled:false}") boolean enabled,
      @Value("${devforge.sandbox.docker-binary:docker}") String dockerBinary) {
    if (enabled) {
      log.info("Code execution sandbox enabled, using {}", dockerBinary);
      return new DockerSandbox(dockerBinary);
    }
    log.info("Code execution sandbox disabled; every run will be refused");
    return (language, code, limits) -> {
      throw new Sandbox.SandboxUnavailableException(
          "Code execution is not enabled on this deployment");
    };
  }
}
