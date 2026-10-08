package com.devforge.ai.authservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devforge.ai.authservice.repository.AuditLogRepository;
import com.devforge.ai.authservice.repository.EmailVerificationTokenRepository;
import com.devforge.ai.authservice.repository.LoginHistoryRepository;
import com.devforge.ai.authservice.repository.PasswordResetTokenRepository;
import com.devforge.ai.authservice.repository.RefreshTokenRepository;
import com.devforge.ai.authservice.repository.UserRepository;
import com.devforge.ai.authservice.service.AuditService;
import com.devforge.ai.authservice.service.EmailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Covers the Phase 1 security controls: refresh-token rotation with reuse detection,
 * brute-force throttling, and the audit trail.
 */
@SpringBootTest
class AuthSecurityControlsTest {

  private static final String SIGNUP = "/api/v1/auth/signup";
  private static final String LOGIN = "/api/v1/auth/login";
  private static final String REFRESH = "/api/v1/auth/refresh";
  private static final String VERIFY = "/api/v1/auth/verify-email";
  private static final String COOKIE = "DEVFORGE_REFRESH_TOKEN";

  private static final String EMAIL = "grace@example.com";
  private static final String USERNAME = "grace";
  private static final String PASSWORD = "Str0ng-Passw0rd!";

  @Autowired private WebApplicationContext context;
  @Autowired private org.springframework.context.ApplicationContext applicationContext;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private RefreshTokenRepository refreshTokenRepository;
  @Autowired private EmailVerificationTokenRepository emailVerificationTokenRepository;
  @Autowired private PasswordResetTokenRepository passwordResetTokenRepository;
  @Autowired private LoginHistoryRepository loginHistoryRepository;
  @Autowired private AuditLogRepository auditLogRepository;

  @MockitoBean private EmailService emailService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() throws Exception {
    mockMvc = MockMvcBuilders.webAppContextSetup(context)
        .apply(SecurityMockMvcConfigurers.springSecurity())
        .build();

    AuthTestData.clear(applicationContext);

    registerVerifiedUser();
  }

  private void registerVerifiedUser() throws Exception {
    var body = objectMapper.writeValueAsString(Map.of(
        "firstName", "Grace", "lastName", "Hopper",
        "username", USERNAME, "email", EMAIL,
        "password", PASSWORD, "organization", "DevForge"));
    mockMvc.perform(post(SIGNUP).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated());

    var token = emailVerificationTokenRepository.findAll().get(0).getToken();
    mockMvc.perform(post(VERIFY).param("token", token)).andExpect(status().isOk());
  }

  private MvcResult login(String password) throws Exception {
    var body = objectMapper.writeValueAsString(
        Map.of("usernameOrEmail", USERNAME, "password", password));
    return mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(body))
        .andReturn();
  }

  /** Reads the refresh token the server just set, as a browser would. */
  private String refreshCookieFrom(MvcResult result) {
    var cookie = result.getResponse().getCookie(COOKIE);
    assertThat(cookie).as("refresh cookie must be set").isNotNull();
    return cookie.getValue();
  }

  // --- Rotation -----------------------------------------------------------

  @Test
  @DisplayName("refresh issues a different refresh token than the one presented")
  void refreshRotatesTheToken() throws Exception {
    var loginResult = login(PASSWORD);
    assertThat(loginResult.getResponse().getStatus()).isEqualTo(200);
    var original = refreshCookieFrom(loginResult);

    var refreshed = mockMvc.perform(post(REFRESH).cookie(new Cookie(COOKIE, original)))
        .andExpect(status().isOk())
        .andReturn();

    var rotated = refreshCookieFrom(refreshed);
    assertThat(rotated).isNotEqualTo(original);
    // Exactly one live token remains: the replacement.
    assertThat(refreshTokenRepository.findAll()).hasSize(1);
    assertThat(refreshTokenRepository.findAll().get(0).getToken()).isEqualTo(rotated);
  }

  @Test
  @DisplayName("the previous refresh token stops working once rotated")
  void oldRefreshTokenIsRejectedAfterRotation() throws Exception {
    var original = refreshCookieFrom(login(PASSWORD));

    mockMvc.perform(post(REFRESH).cookie(new Cookie(COOKIE, original)))
        .andExpect(status().isOk());

    // Replaying the consumed token must fail. Before rotation existed, this
    // succeeded for the token's full 14-day lifetime.
    mockMvc.perform(post(REFRESH).cookie(new Cookie(COOKIE, original)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("replaying a rotated token revokes every session for the account")
  void reuseOfRotatedTokenRevokesAllSessions() throws Exception {
    var original = refreshCookieFrom(login(PASSWORD));

    var refreshed = mockMvc.perform(post(REFRESH).cookie(new Cookie(COOKIE, original)))
        .andExpect(status().isOk())
        .andReturn();
    var rotated = refreshCookieFrom(refreshed);

    // An attacker replays the stolen, already-consumed token.
    mockMvc.perform(post(REFRESH).cookie(new Cookie(COOKIE, original)))
        .andExpect(status().isUnauthorized());

    // Theft cannot be attributed to a side, so every session is dropped.
    assertThat(refreshTokenRepository.findAll()).isEmpty();

    // The legitimate client's newer token is dead too, forcing a fresh login.
    mockMvc.perform(post(REFRESH).cookie(new Cookie(COOKIE, rotated)))
        .andExpect(status().isUnauthorized());

    assertThat(auditLogRepository.findAll())
        .anyMatch(entry -> AuditService.ACTION_TOKEN_REUSE_DETECTED.equals(entry.getAction()));
  }

  // --- Brute-force throttling --------------------------------------------

  @Test
  @DisplayName("repeated failures block further attempts, even with the correct password")
  void loginIsThrottledAfterRepeatedFailures() throws Exception {
    // Default threshold is 5 failures within the window.
    for (int i = 0; i < 5; i++) {
      assertThat(login("wrong-password-" + i).getResponse().getStatus())
          .as("attempt %d should be rejected", i)
          .isEqualTo(401);
    }

    // The correct password must now also be refused: the account is throttled,
    // which is what caps an attacker's guess rate.
    var blocked = login(PASSWORD);
    assertThat(blocked.getResponse().getStatus()).isEqualTo(401);
    assertThat(blocked.getResponse().getCookie(COOKIE))
        .as("no session may be established while throttled")
        .isNull();

    assertThat(auditLogRepository.findAll())
        .anyMatch(entry -> AuditService.ACTION_LOGIN_BLOCKED.equals(entry.getAction()));
  }

  @Test
  @DisplayName("a throttled response is indistinguishable from a wrong password")
  void throttledResponseDoesNotLeakAccountState() throws Exception {
    var wrong = login("definitely-wrong").getResponse().getContentAsString();
    for (int i = 0; i < 5; i++) {
      login("wrong-password-" + i);
    }
    var throttled = login(PASSWORD).getResponse().getContentAsString();

    // Identical bodies: the caller cannot tell a bad password from a lockout,
    // which would otherwise confirm the account exists and is under attack.
    assertThat(throttled).isEqualTo(wrong);
  }

  @Test
  @DisplayName("a successful login is not blocked below the threshold")
  void loginSucceedsBelowThreshold() throws Exception {
    for (int i = 0; i < 4; i++) {
      login("wrong-password-" + i);
    }

    assertThat(login(PASSWORD).getResponse().getStatus()).isEqualTo(200);
  }

  // --- Audit trail --------------------------------------------------------

  @Test
  @DisplayName("a successful login is recorded in login history and the audit log")
  void successfulLoginIsAudited() throws Exception {
    login(PASSWORD);

    var history = loginHistoryRepository.findAll();
    assertThat(history).hasSize(1);
    assertThat(history.get(0).getStatus()).isEqualTo(AuditService.STATUS_SUCCESS);
    assertThat(history.get(0).getLoginType()).isEqualTo(AuditService.LOGIN_TYPE_PASSWORD);

    assertThat(auditLogRepository.findAll())
        .anyMatch(entry -> AuditService.ACTION_LOGIN_SUCCESS.equals(entry.getAction()));
  }

  @Test
  @DisplayName("a failed login is recorded against the account")
  void failedLoginIsAudited() throws Exception {
    login("wrong-password");

    var history = loginHistoryRepository.findAll();
    assertThat(history).hasSize(1);
    assertThat(history.get(0).getStatus()).isEqualTo(AuditService.STATUS_FAILURE);

    assertThat(auditLogRepository.findAll())
        .anyMatch(entry -> AuditService.ACTION_LOGIN_FAILURE.equals(entry.getAction()));
  }

  @Test
  @DisplayName("an attempt against an unknown username writes no login-history row")
  void unknownUsernameIsNotRecordedPerAccount() throws Exception {
    var body = objectMapper.writeValueAsString(
        Map.of("usernameOrEmail", "no-such-user", "password", "whatever"));
    mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnauthorized());

    // login_history.user_id is NOT NULL, and a per-username row would itself be
    // an enumeration signal. These belong in IP-level limiting instead.
    assertThat(loginHistoryRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName("audit details never contain the submitted password")
  void auditTrailDoesNotStoreCredentials() throws Exception {
    login(PASSWORD);
    login("wrong-password");

    assertThat(auditLogRepository.findAll())
        .allSatisfy(entry -> {
          if (entry.getDetails() != null) {
            assertThat(entry.getDetails()).doesNotContain(PASSWORD).doesNotContain("wrong-password");
          }
        });
    assertThat(loginHistoryRepository.findAll())
        .allSatisfy(entry -> {
          if (entry.getFailureReason() != null) {
            assertThat(entry.getFailureReason()).doesNotContain(PASSWORD);
          }
        });
  }
}
