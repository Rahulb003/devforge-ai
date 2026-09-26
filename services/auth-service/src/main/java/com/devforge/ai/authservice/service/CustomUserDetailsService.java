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

  @Override
  @Transactional
  public UserDetails loadUserByUsername(String usernameOrId) {
    try {
      var userId = UUID.fromString(usernameOrId);
      return userRepository.findById(userId)
          .map(UserPrincipal::fromEntity)
          .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    } catch (IllegalArgumentException ex) {
      return userRepository.findByUsernameIgnoreCase(usernameOrId)
          .map(UserPrincipal::fromEntity)
          .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }
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
