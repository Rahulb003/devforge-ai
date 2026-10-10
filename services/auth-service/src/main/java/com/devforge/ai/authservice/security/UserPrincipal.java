package com.devforge.ai.authservice.security;

import com.devforge.ai.authservice.entity.UserEntity;
import com.devforge.ai.authservice.model.AccountStatus;
import java.util.Collection;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

@Getter
public class UserPrincipal implements UserDetails {

  private final UUID id;
  private final String username;
  private final String password;
  private final String email;
  private final boolean enabled;
  private final Collection<? extends GrantedAuthority> authorities;
  private final AccountStatus status;
  /** Whether the account proved it controls its email address; carried into the access token. */
  private final boolean emailVerified;

  public UserPrincipal(UUID id, String username, String password, String email, boolean enabled, Collection<? extends GrantedAuthority> authorities, AccountStatus status) {
    this(id, username, password, email, enabled, authorities, status, false);
  }

  public UserPrincipal(UUID id, String username, String password, String email, boolean enabled,
      Collection<? extends GrantedAuthority> authorities, AccountStatus status, boolean emailVerified) {
    this.id = id;
    this.username = username;
    this.password = password;
    this.email = email;
    this.enabled = enabled;
    this.authorities = authorities;
    this.status = status;
    this.emailVerified = emailVerified;
  }

  public static UserPrincipal fromEntity(UserEntity user) {
    return new UserPrincipal(
        user.getId(),
        user.getUsername(),
        user.getPasswordHash(),
        user.getEmail(),
        user.getStatus() == AccountStatus.ACTIVE,
        user.getRoles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role.getName().name())).collect(Collectors.toSet()),
        user.getStatus(),
        user.isEmailVerified());
  }

  public UserEntity toEntity() {
    var user = new UserEntity();
    user.setId(id);
    user.setUsername(username);
    user.setPasswordHash(password);
    user.setEmail(email);
    user.setStatus(status);
    return user;
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return authorities;
  }

  @Override
  public String getPassword() {
    return password;
  }

  @Override
  public String getUsername() {
    return username;
  }

  @Override
  public boolean isAccountNonExpired() {
    return enabled;
  }

  @Override
  public boolean isAccountNonLocked() {
    return status != AccountStatus.LOCKED && status != AccountStatus.SUSPENDED && status != AccountStatus.DISABLED;
  }

  @Override
  public boolean isCredentialsNonExpired() {
    return enabled;
  }

  @Override
  public boolean isEnabled() {
    return enabled;
  }
}
