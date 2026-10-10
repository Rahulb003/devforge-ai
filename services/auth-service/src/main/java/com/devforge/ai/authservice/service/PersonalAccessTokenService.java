package com.devforge.ai.authservice.service;

import com.devforge.ai.authservice.entity.PersonalAccessTokenEntity;
import com.devforge.ai.authservice.model.AccountStatus;
import com.devforge.ai.authservice.repository.PersonalAccessTokenRepository;
import com.devforge.ai.authservice.repository.UserRepository;
import com.devforge.ai.authservice.security.JwtTokenProvider;
import com.devforge.ai.common.exception.ResourceNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Personal access tokens, for git clients.
 *
 * <p>A token is only ever exchanged by git-service, over an endpoint the gateway does not route,
 * for a short-lived access token that never leaves the cluster. So a leaked personal token can
 * clone and push, with the owner's project roles, but cannot call the rest of the API, change
 * the account or mint more tokens.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonalAccessTokenService {

  /** Recognisable in a leaked config file or a secret scanner's rules. */
  static final String PREFIX = "dfp_";
  static final int MAX_ACTIVE_TOKENS = 50;
  static final int MAX_LIFETIME_DAYS = 365;
  /** Long enough for one large clone or push; a git client re-authenticates per request anyway. */
  static final Duration EXCHANGED_TOKEN_TTL = Duration.ofMinutes(5);

  private static final SecureRandom RANDOM = new SecureRandom();

  private final PersonalAccessTokenRepository tokens;
  private final UserRepository users;
  private final JwtTokenProvider jwtTokenProvider;
  private final AuditService auditService;

  public record TokenView(
      UUID id, String name, String prefix, Instant createdAt, Instant expiresAt,
      Instant lastUsedAt) {}

  /** The only time the token itself is returned. */
  public record CreatedToken(TokenView details, String token) {}

  @Transactional
  public CreatedToken create(UUID userId, String name, Integer expiresInDays) {
    var trimmedName = requireValidName(name);
    var days = expiresInDays == null ? 90 : expiresInDays;
    if (days < 1 || days > MAX_LIFETIME_DAYS) {
      throw new IllegalArgumentException(
          "A token must expire within 1 to " + MAX_LIFETIME_DAYS + " days");
    }
    if (tokens.countByUserIdAndRevokedAtIsNull(userId) >= MAX_ACTIVE_TOKENS) {
      throw new IllegalArgumentException(
          "At most " + MAX_ACTIVE_TOKENS + " tokens may be active; revoke one first");
    }

    var bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    var token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    var entity = tokens.save(PersonalAccessTokenEntity.builder()
        .userId(userId)
        .name(trimmedName)
        .tokenHash(hash(token))
        .tokenPrefix(token.substring(0, 12))
        .createdAt(now)
        .expiresAt(now.plus(Duration.ofDays(days)))
        .build());

    users.findById(userId).ifPresent(user -> auditService.record(user, "ACCESS_TOKEN_CREATED", null,
        "Personal access token \"%s\" (%s) created, expiring %s."
            .formatted(trimmedName, entity.getTokenPrefix(), entity.getExpiresAt())));
    return new CreatedToken(view(entity), token);
  }

  @Transactional(readOnly = true)
  public List<TokenView> list(UUID userId) {
    return tokens.findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(userId).stream()
        .map(PersonalAccessTokenService::view)
        .toList();
  }

  /** Another user's token is reported as absent, like a session. */
  @Transactional
  public void revoke(UUID userId, UUID tokenId) {
    var token = tokens.findByIdAndUserId(tokenId, userId)
        .filter(t -> t.getRevokedAt() == null)
        .orElseThrow(() -> new ResourceNotFoundException("Token not found"));
    token.setRevokedAt(Instant.now());
    users.findById(userId).ifPresent(user -> auditService.record(user, "ACCESS_TOKEN_REVOKED", null,
        "Personal access token \"%s\" (%s) revoked.".formatted(token.getName(), token.getTokenPrefix())));
  }

  /**
   * A short-lived access token for the token's owner, or empty when the token is unknown,
   * revoked, expired, or belongs to an account that may not sign in.
   *
   * <p>The account is checked on every exchange, so locking or disabling an account stops its
   * tokens at once rather than when they expire.
   */
  @Transactional
  public Optional<String> exchange(String token) {
    if (token == null || !token.startsWith(PREFIX) || token.length() > 100) {
      return Optional.empty();
    }
    var now = Instant.now();
    return tokens.findByTokenHash(hash(token))
        .filter(t -> t.isUsableAt(now))
        .flatMap(t -> users.findById(t.getUserId())
            .filter(user -> user.getStatus() == AccountStatus.ACTIVE)
            .map(user -> {
              t.setLastUsedAt(now);
              return jwtTokenProvider.createAccessToken(user, EXCHANGED_TOKEN_TTL);
            }));
  }

  private static String requireValidName(String name) {
    if (name == null) {
      throw new IllegalArgumentException("A token needs a name");
    }
    // Control characters checked before trimming: trim() strips them, which would quietly accept
    // a name that is not what was typed.
    if (name.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("A token name cannot contain control characters");
    }
    var trimmed = name.trim();
    if (trimmed.isEmpty() || trimmed.length() > 100) {
      throw new IllegalArgumentException("A token name must be 1 to 100 characters");
    }
    return trimmed;
  }

  static String hash(String token) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is required of every JVM", ex);
    }
  }

  private static TokenView view(PersonalAccessTokenEntity t) {
    return new TokenView(
        t.getId(), t.getName(), t.getTokenPrefix(), t.getCreatedAt(), t.getExpiresAt(),
        t.getLastUsedAt());
  }
}
