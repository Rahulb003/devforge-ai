package com.devforge.ai.authservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PasswordResetRequest {
  @NotBlank(message = "New password is required")
  @Size(min = 12, message = "Password must be at least 12 characters")
  private String newPassword;

  @NotBlank(message = "Token is required")
  private String token;
}
