package com.devforge.ai.authservice.dto;

import com.devforge.ai.authservice.model.AccountStatus;
import com.devforge.ai.authservice.model.OAuthProvider;
import java.time.Instant;
import java.util.Set;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class UserProfileResponse {
  private final String id;
  private final String firstName;
  private final String lastName;
  private final String username;
  private final String email;
  private final String phone;
  private final String avatarUrl;
  private final String organization;
  private final Set<String> roles;
  private final AccountStatus status;
  private final OAuthProvider oauthProvider;
  private final boolean emailVerified;
  private final String timezone;
  private final String language;
  private final boolean darkMode;
  private final Instant createdAt;
  private final Instant updatedAt;
  private final Instant lastLogin;
}
