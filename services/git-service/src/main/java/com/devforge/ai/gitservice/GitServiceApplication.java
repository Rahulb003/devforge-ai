package com.devforge.ai.gitservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.devforge.ai")
public class GitServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(GitServiceApplication.class, args);
  }
}
