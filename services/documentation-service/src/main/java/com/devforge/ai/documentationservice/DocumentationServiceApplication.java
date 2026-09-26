package com.devforge.ai.documentationservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.devforge.ai")
public class DocumentationServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(DocumentationServiceApplication.class, args);
  }
}
