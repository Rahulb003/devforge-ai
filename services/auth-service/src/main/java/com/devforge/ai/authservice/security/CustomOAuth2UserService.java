package com.devforge.ai.authservice.security;

import com.devforge.ai.authservice.entity.OAuthAccountEntity;
import com.devforge.ai.authservice.entity.UserEntity;
import com.devforge.ai.authservice.model.AccountStatus;
import com.devforge.ai.authservice.model.OAuthProvider;
import com.devforge.ai.authservice.model.RoleName;
import com.devforge.ai.authservice.repository.OAuthAccountRepository;
import com.devforge.ai.authservice.repository.RoleRepository;
import com.devforge.ai.authservice.repository.UserRepository;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves an OAuth2 login to a DevForge user, creating or linking the account as needed.
 *
 * <p>Previously this delegated to the default implementation and persisted nothing, so signing in
 * with Google produced a transient principal, no user row, and no way to authorise anything.
 *
 * <p>Linking rule: an existing local account is matched by <em>verified</em> email. Matching on an
 * unverified address would be an account-takeover path — register locally with someone else's
 * address, wait for them to sign in with the real provider, and inherit their account.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

  private final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();

  private final UserRepository userRepository;
  private final OAuthAccountRepository oauthAccountRepository;
  private final RoleRepository roleRepository;

  @Override
  @Transactional
  public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
    var oauthUser = delegate.loadUser(userRequest);
    var registrationId = userRequest.getClientRegistration().getRegistrationId();
    var provider = resolveProvider(registrationId);
    var attributes = normalise(provider, oauthUser);

    if (attributes.email() == null || attributes.email().isBlank()) {
      // Some providers can withhold the email. Without one there is no safe way to link or
      // create an account, so the login is refused rather than guessed at.
      throw new OAuth2AuthenticationException(
          new OAuth2Error("email_not_available"),
          "The identity provider did not supply an email address.");
    }

    var user = findOrCreateUser(provider, attributes);
    linkProviderAccount(provider, attributes.providerId(), user);

    user.setLastLogin(Instant.now());
    userRepository.save(user);

    var authorities = user.getRoles().stream()
        .map(role -> new SimpleGrantedAuthority("ROLE_" + role.getName().name()))
        .collect(java.util.stream.Collectors.toSet());

    return new org.springframework.security.oauth2.core.user.DefaultOAuth2User(
        authorities, oauthUser.getAttributes(), attributes.nameAttributeKey());
  }

  private UserEntity findOrCreateUser(OAuthProvider provider, ProviderAttributes attributes) {
    // 1. Already linked to this provider identity.
    var linked = oauthAccountRepository.findByProviderAndProviderId(provider, attributes.providerId());
    if (linked.isPresent()) {
      return linked.get().getUser();
    }

    // 2. A local account with the same email. Only linked when that address is verified.
    var existing = userRepository.findByEmailIgnoreCase(attributes.email());
    if (existing.isPresent()) {
      var user = existing.get();
      if (!user.isEmailVerified()) {
        throw new OAuth2AuthenticationException(
            new OAuth2Error("email_not_verified"),
            "An account with this email exists but its address is not verified. "
                + "Verify it before linking a provider.");
      }
      log.info("Linking {} identity to existing user {}", provider, user.getId());
      return user;
    }

    // 3. First time: provision a new account. It is already verified, because the provider
    // asserts the address, and has no password — local login is impossible until one is set.
    var developerRole = roleRepository.findByName(RoleName.DEVELOPER)
        .orElseThrow(() -> new IllegalStateException("Default role not configured"));

    var user = UserEntity.builder()
        .firstName(attributes.firstName())
        .lastName(attributes.lastName())
        .username(uniqueUsername(attributes.email()))
        .email(attributes.email().toLowerCase(Locale.ROOT))
        .avatarUrl(attributes.avatarUrl())
        .status(AccountStatus.ACTIVE)
        .oauthProvider(provider)
        .passwordHash(null)
        .emailVerified(true)
        .darkMode(true)
        .roles(Set.of(developerRole))
        .build();

    log.info("Provisioning new user from {} login", provider);
    return userRepository.save(user);
  }

  private void linkProviderAccount(OAuthProvider provider, String providerId, UserEntity user) {
    if (oauthAccountRepository.findByProviderAndProviderId(provider, providerId).isPresent()) {
      return;
    }
    oauthAccountRepository.save(OAuthAccountEntity.builder()
        .id(UUID.randomUUID())
        .provider(provider)
        .providerId(providerId)
        .user(user)
        .createdAt(Instant.now())
        .build());
  }

  /**
   * Derives a username from the email local part, adding a suffix on collision.
   *
   * <p>Usernames are globally unique, so two providers' "jane@" addresses cannot both be "jane".
   */
  private String uniqueUsername(String email) {
    var base = email.split("@")[0].replaceAll("[^a-zA-Z0-9._-]", "").toLowerCase(Locale.ROOT);
    if (base.isBlank()) {
      base = "user";
    }
    if (base.length() > 40) {
      base = base.substring(0, 40);
    }
    var candidate = base;
    var suffix = 1;
    while (userRepository.findByUsernameIgnoreCase(candidate).isPresent()) {
      candidate = base + suffix++;
    }
    return candidate;
  }

  private OAuthProvider resolveProvider(String registrationId) {
    try {
      return OAuthProvider.valueOf(registrationId.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new OAuth2AuthenticationException(
          new OAuth2Error("unsupported_provider"), "Unsupported provider: " + registrationId);
    }
  }

  /** Provider-specific attribute shapes, flattened to what this service needs. */
  private record ProviderAttributes(
      String providerId,
      String email,
      String firstName,
      String lastName,
      String avatarUrl,
      String nameAttributeKey) {}

  private ProviderAttributes normalise(OAuthProvider provider, OAuth2User oauthUser) {
    var attributes = oauthUser.getAttributes();
    return switch (provider) {
      case GOOGLE -> new ProviderAttributes(
          asString(attributes.get("sub")),
          asString(attributes.get("email")),
          asString(attributes.get("given_name")),
          asString(attributes.get("family_name")),
          asString(attributes.get("picture")),
          "sub");
      // Microsoft Graph returns "id" and uses mail, falling back to userPrincipalName for
      // accounts without a mailbox.
      case MICROSOFT -> new ProviderAttributes(
          asString(attributes.get("id")),
          attributes.get("mail") != null
              ? asString(attributes.get("mail"))
              : asString(attributes.get("userPrincipalName")),
          asString(attributes.get("givenName")),
          asString(attributes.get("surname")),
          null,
          "id");
      case LOCAL -> throw new OAuth2AuthenticationException(
          new OAuth2Error("unsupported_provider"), "LOCAL is not an OAuth provider");
    };
  }

  private String asString(Object value) {
    return value != null ? value.toString() : null;
  }
}
