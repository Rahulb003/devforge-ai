package com.devforge.ai.authservice.service;

import com.devforge.ai.authservice.repository.RefreshTokenRepository;
import com.devforge.ai.authservice.repository.UserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revokes sessions in a transaction of its own.
 *
 * <p>This exists because revocation usually happens on a path that then <em>rejects</em> the
 * request. Refresh-token reuse is the motivating case: the handler deletes the account's tokens
 * and throws to signal the failure — but with both in one transaction, the throw rolls the delete
 * back, so the sessions the code believed it had revoked stayed live. A test that replayed a
 * rotated token caught exactly that.
 *
 * <p>{@link Propagation#REQUIRES_NEW} runs and commits the revocation independently, so it
 * survives the caller's rollback. It is a separate bean rather than a method on
 * {@code AuthService} because Spring's proxying ignores propagation on self-invocation — calling
 * it from within the same class would silently join the caller's transaction and reintroduce the
 * bug.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionRevocationService {

  private final RefreshTokenRepository refreshTokenRepository;
  private final UserRepository userRepository;

  /**
   * Deletes every stored refresh token for the user, committing immediately.
   *
   * @return true if the user existed and revocation was attempted.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean revokeAllSessions(UUID userId) {
    var user = userRepository.findById(userId).orElse(null);
    if (user == null) {
      return false;
    }
    refreshTokenRepository.deleteByUser(user);
    refreshTokenRepository.flush();
    log.warn("Revoked all refresh tokens for user {}.", userId);
    return true;
  }
}
