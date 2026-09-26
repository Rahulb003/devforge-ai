package com.devforge.ai.authservice.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginRequest {
  @NotBlank(message = "Username or email is required")
  private String usernameOrEmail;

  @NotBlank(message = "Password is required")
  private String password;

  private boolean rememberMe;
}
