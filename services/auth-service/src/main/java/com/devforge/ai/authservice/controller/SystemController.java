package com.devforge.ai.authservice.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

  @GetMapping("/health")
  public ResponseEntity<String> getHealth() {
    return ResponseEntity.ok("AUTH_SERVICE_UP");
  }
}
