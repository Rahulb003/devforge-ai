package com.devforge.ai.authservice.entity;

import com.devforge.ai.authservice.model.AccountStatus;
import com.devforge.ai.authservice.model.OAuthProvider;
import com.devforge.ai.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "users")
public class UserEntity extends BaseEntity {

  @Column(name = "first_name", length = 100)
  private String firstName;

  @Column(name = "last_name", length = 100)
  private String lastName;

  @Column(name = "username", nullable = false, unique = true, length = 50)
  private String username;

  @Column(name = "email", nullable = false, unique = true, length = 150)
  private String email;

  @Column(name = "phone", length = 30)
  private String phone;

  @Column(name = "avatar_url", length = 512)
  private String avatarUrl;

  @Column(name = "organization", length = 150)
  private String organization;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 50)
  private AccountStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "oauth_provider", nullable = false, length = 50)
  private OAuthProvider oauthProvider;

  @Column(name = "password_hash", length = 200)
  private String passwordHash;

  @Column(name = "email_verified", nullable = false)
  private boolean emailVerified;

  @Column(name = "timezone", length = 100)
  private String timezone;

  @Column(name = "language", length = 50)
  private String language;

  @Column(name = "dark_mode", nullable = false)
  private boolean darkMode;

  /** Whether TOTP two-factor authentication is active for this account. */
  @Column(name = "mfa_enabled", nullable = false)
  private boolean mfaEnabled;

  /**
   * Base32 TOTP shared secret.
   *
   * <p>A credential in its own right: anyone holding it can mint valid codes. It must never be
   * logged, returned by an API after enrolment, or included in a profile response.
   */
  @Column(name = "mfa_secret", length = 255)
  private String mfaSecret;

  @Column(name = "mfa_enrolled_at")
  private Instant mfaEnrolledAt;

  @Column(name = "last_login")
  private Instant lastLogin;

  @ManyToMany(fetch = FetchType.EAGER)
  @JoinTable(
      name = "user_roles",
      joinColumns = @JoinColumn(name = "user_id"),
      inverseJoinColumns = @JoinColumn(name = "role_id")
  )
  private Set<RoleEntity> roles = new HashSet<>();

  @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
  private List<OAuthAccountEntity> oauthAccounts = new ArrayList<>();
}
