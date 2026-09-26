package com.devforge.ai.reviewservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.devforge.ai")
public class ReviewServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(ReviewServiceApplication.class, args);
  }
}
