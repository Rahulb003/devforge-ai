package com.devforge.ai.taskservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.devforge.ai")
public class TaskServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(TaskServiceApplication.class, args);
  }
}
