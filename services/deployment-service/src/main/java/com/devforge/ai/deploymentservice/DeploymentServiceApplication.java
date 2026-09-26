package com.devforge.ai.deploymentservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.devforge.ai")
public class DeploymentServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(DeploymentServiceApplication.class, args);
  }
}
