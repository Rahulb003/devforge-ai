package com.devforge.ai.authservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SignupRequest {

  @NotBlank(message = "First name is required")
  @Size(max = 100)
  private String firstName;

  @NotBlank(message = "Last name is required")
  @Size(max = 100)
  private String lastName;

  @NotBlank(message = "Username is required")
  @Size(min = 3, max = 50)
  private String username;

  @NotBlank(message = "Email is required")
  @Email(message = "Email must be valid")
  private String email;

  @NotBlank(message = "Password is required")
  @Size(min = 12, message = "Password must be at least 12 characters")
  @Pattern(regexp = "(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[^a-zA-Z0-9]).*", message = "Password must include uppercase, lowercase, number, and special character")
  private String password;

  @NotBlank(message = "Organization is required")
  @Size(max = 150)
  private String organization;
}
