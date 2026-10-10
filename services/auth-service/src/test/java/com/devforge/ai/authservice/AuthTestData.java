package com.devforge.ai.authservice;

import com.devforge.ai.authservice.repository.AuditLogRepository;
import com.devforge.ai.authservice.repository.EmailVerificationTokenRepository;
import com.devforge.ai.authservice.repository.LoginHistoryRepository;
import com.devforge.ai.authservice.repository.MfaBackupCodeRepository;
import com.devforge.ai.authservice.repository.OAuthAccountRepository;
import com.devforge.ai.authservice.repository.PasswordResetTokenRepository;
import com.devforge.ai.authservice.repository.RefreshTokenRepository;
import com.devforge.ai.authservice.repository.UserRepository;
import java.util.List;
import org.springframework.context.ApplicationContext;
import org.springframework.data.repository.CrudRepository;

/**
 * Empties every table that references a user, children first, then the users.
 *
 * <p>The test classes share one cached context and so one database. Each used to keep its own list
 * of tables to clear, and three of the five lists left out MFA backup codes. Whichever of those ran
 * after the MFA tests could not delete the users those codes still referenced. Locally the classes
 * happened to run in an order that hid it; on Linux CI they did not, and every push failed. One list
 * cannot drift like that.
 */
final class AuthTestData {

  private static final List<Class<? extends CrudRepository<?, ?>>> CHILDREN_FIRST = List.of(
      MfaBackupCodeRepository.class,
      OAuthAccountRepository.class,
      LoginHistoryRepository.class,
      AuditLogRepository.class,
      RefreshTokenRepository.class,
      com.devforge.ai.authservice.repository.PersonalAccessTokenRepository.class,
      EmailVerificationTokenRepository.class,
      PasswordResetTokenRepository.class,
      UserRepository.class);

  private AuthTestData() {}

  static void clear(ApplicationContext context) {
    CHILDREN_FIRST.forEach(type -> context.getBean(type).deleteAll());
  }
}
