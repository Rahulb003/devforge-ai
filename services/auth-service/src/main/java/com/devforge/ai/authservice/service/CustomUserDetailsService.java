package com.devforge.ai.authservice.service;

import com.devforge.ai.authservice.entity.UserEntity;
import com.devforge.ai.authservice.repository.UserRepository;
import com.devforge.ai.authservice.security.UserPrincipal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

  private final UserRepository userRepository;

  /**
   * Resolves a login identifier, which may be a user id, a username or an email address.
   *
   * <p>The email branch previously did not exist: the login form offers "username or email", and
   * the API field is literally called {@code usernameOrEmail}, but only usernames resolved here.
   * Anyone who signed up and then typed their email address — the obvious thing to do — was told
   * their credentials were invalid, with a correct password.
   *
   * <p>The id branch stays first because {@link com.devforge.ai.authservice.security.JwtAuthenticationFilter}
   * loads the principal by the token subject, which is a UUID.
   */
  @Override
  @Transactional
  public UserDetails loadUserByUsername(String identifier) {
    return findByIdentifier(identifier)
        .map(UserPrincipal::fromEntity)
        .orElseThrow(() -> new UsernameNotFoundException("User not found"));
  }

  private java.util.Optional<UserEntity> findByIdentifier(String identifier) {
    if (identifier == null || identifier.isBlank()) {
      return java.util.Optional.empty();
    }

    // A UUID identifier only ever comes from a verified token subject.
    try {
      var byId = userRepository.findById(UUID.fromString(identifier));
      if (byId.isPresent()) {
        return byId;
      }
    } catch (IllegalArgumentException ignored) {
      // Not a UUID, so it is a username or an email. Fall through.
    }

    var byUsername = userRepository.findByUsernameIgnoreCase(identifier);
    return byUsername.isPresent() ? byUsername : userRepository.findByEmailIgnoreCase(identifier);
  }

  @Transactional
  public UserDetails loadUserByEmail(String email) {
    return userRepository.findByEmailIgnoreCase(email)
        .map(UserPrincipal::fromEntity)
        .orElseThrow(() -> new UsernameNotFoundException("User not found"));
  }

  @Transactional
  public UserEntity getUserByEmail(String email) {
    return userRepository.findByEmailIgnoreCase(email).orElseThrow(() -> new UsernameNotFoundException("User not found"));
  }
}
