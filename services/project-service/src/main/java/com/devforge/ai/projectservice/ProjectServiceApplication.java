package com.devforge.ai.projectservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.devforge.ai")
public class ProjectServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(ProjectServiceApplication.class, args);
  }
}
