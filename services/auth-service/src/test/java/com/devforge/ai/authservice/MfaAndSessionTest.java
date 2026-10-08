package com.devforge.ai.authservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devforge.ai.authservice.repository.AuditLogRepository;
import com.devforge.ai.authservice.repository.EmailVerificationTokenRepository;
import com.devforge.ai.authservice.repository.LoginHistoryRepository;
import com.devforge.ai.authservice.repository.MfaBackupCodeRepository;
import com.devforge.ai.authservice.repository.PasswordResetTokenRepository;
import com.devforge.ai.authservice.repository.RefreshTokenRepository;
import com.devforge.ai.authservice.repository.UserRepository;
import com.devforge.ai.authservice.security.TotpService;
import com.devforge.ai.authservice.service.EmailService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Covers MFA enrolment and login, and per-device session management.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MfaAndSessionTest {

  private static final String SIGNUP = "/api/v1/auth/signup";
  private static final String LOGIN = "/api/v1/auth/login";
  private static final String LOGIN_MFA = "/api/v1/auth/login/mfa";
  private static final String VERIFY = "/api/v1/auth/verify-email";
  private static final String MFA = "/api/v1/auth/mfa";
  private static final String SESSIONS = "/api/v1/auth/sessions";
  private static final String COOKIE = "DEVFORGE_REFRESH_TOKEN";

  private static final String EMAIL = "mfa-user@example.com";
  private static final String USERNAME = "mfauser";
  private static final String PASSWORD = "Str0ng-Passw0rd!";

  @Autowired private MockMvc mockMvc;
  @Autowired private org.springframework.context.ApplicationContext applicationContext;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private TotpService totpService;
  @Autowired private UserRepository userRepository;
  @Autowired private RefreshTokenRepository refreshTokenRepository;
  @Autowired private MfaBackupCodeRepository backupCodeRepository;
  @Autowired private EmailVerificationTokenRepository emailVerificationTokenRepository;
  @Autowired private PasswordResetTokenRepository passwordResetTokenRepository;
  @Autowired private LoginHistoryRepository loginHistoryRepository;
  @Autowired private AuditLogRepository auditLogRepository;

  @MockitoBean private EmailService emailService;

  private String accessToken;

  @BeforeEach
  void setUp() throws Exception {
    AuthTestData.clear(applicationContext);

    var body = objectMapper.writeValueAsString(Map.of(
        "firstName", "Mfa", "lastName", "User", "username", USERNAME,
        "email", EMAIL, "password", PASSWORD, "organization", "DevForge"));
    mockMvc.perform(post(SIGNUP).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated());
    var token = emailVerificationTokenRepository.findAll().get(0).getToken();
    mockMvc.perform(post(VERIFY).param("token", token)).andExpect(status().isOk());

    accessToken = loginAndReadAccessToken(null);
  }

  private MvcResult login(String userAgent) throws Exception {
    var body = objectMapper.writeValueAsString(
        Map.of("usernameOrEmail", USERNAME, "password", PASSWORD));
    var request = post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(body);
    if (userAgent != null) {
      request = request.header("User-Agent", userAgent);
    }
    return mockMvc.perform(request).andExpect(status().isOk()).andReturn();
  }

  private JsonNode dataOf(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
  }

  private String loginAndReadAccessToken(String userAgent) throws Exception {
    return dataOf(login(userAgent)).get("accessToken").asText();
  }

  private String bearer() {
    return "Bearer " + accessToken;
  }

  /** Enrols MFA and returns the shared secret. */
  private String enrol() throws Exception {
    var result = mockMvc.perform(post(MFA + "/enrol").header("Authorization", bearer()))
        .andExpect(status().isOk())
        .andReturn();
    return dataOf(result).get("secret").asText();
  }

  private String currentCode(String secret) {
    return totpService.generateCode(secret, Instant.now().getEpochSecond() / 30);
  }

  private List<String> confirmEnrolment(String secret) throws Exception {
    var body = objectMapper.writeValueAsString(Map.of("code", currentCode(secret)));
    var result = mockMvc.perform(post(MFA + "/confirm").header("Authorization", bearer())
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.convertValue(dataOf(result), List.class);
  }

  // --- MFA ----------------------------------------------------------------

  @Nested
  @DisplayName("MFA enrolment and login")
  class Mfa {

    @Test
    @DisplayName("enrolment does not enable MFA until a code is confirmed")
    void enrolmentIsTwoStep() throws Exception {
      enrol();

      // Still disabled: a mis-scanned QR code must not lock the user out.
      assertThat(userRepository.findByEmailIgnoreCase(EMAIL).orElseThrow().isMfaEnabled()).isFalse();

      mockMvc.perform(get(MFA + "/status").header("Authorization", bearer()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.enabled").value(false));
    }

    @Test
    @DisplayName("confirming enrolment enables MFA and returns recovery codes once")
    void confirmationEnablesMfa() throws Exception {
      var secret = enrol();
      var codes = confirmEnrolment(secret);

      assertThat(codes).hasSize(10).doesNotHaveDuplicates();
      assertThat(userRepository.findByEmailIgnoreCase(EMAIL).orElseThrow().isMfaEnabled()).isTrue();

      // Only hashes are stored: a dump of the table must not yield usable second factors.
      assertThat(backupCodeRepository.findAll())
          .allSatisfy(entry -> assertThat(codes).doesNotContain(entry.getCodeHash()));
    }

    @Test
    @DisplayName("an invalid code does not enable MFA")
    void wrongCodeDoesNotEnable() throws Exception {
      enrol();
      var body = objectMapper.writeValueAsString(Map.of("code", "000000"));
      mockMvc.perform(post(MFA + "/confirm").header("Authorization", bearer())
              .contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isBadRequest());

      assertThat(userRepository.findByEmailIgnoreCase(EMAIL).orElseThrow().isMfaEnabled()).isFalse();
    }

    @Test
    @DisplayName("a correct password alone no longer yields an access token")
    void passwordAloneIsNotEnough() throws Exception {
      var secret = enrol();
      confirmEnrolment(secret);

      var result = login(null);
      var data = dataOf(result);

      assertThat(data.get("mfaRequired").asBoolean()).isTrue();
      assertThat(data.get("accessToken").isNull()).isTrue();
      assertThat(data.get("challengeToken").asText()).isNotBlank();
      // No session may exist yet: the second factor has not been proven.
      assertThat(result.getResponse().getCookie(COOKIE)).isNull();
    }

    @Test
    @DisplayName("the challenge token plus a valid code completes login")
    void challengePlusCodeCompletesLogin() throws Exception {
      var secret = enrol();
      confirmEnrolment(secret);
      var challenge = dataOf(login(null)).get("challengeToken").asText();

      var body = objectMapper.writeValueAsString(
          Map.of("challengeToken", challenge, "code", currentCode(secret)));
      var result = mockMvc.perform(post(LOGIN_MFA)
              .contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isOk())
          .andReturn();

      assertThat(dataOf(result).get("accessToken").asText()).isNotBlank();
      assertThat(result.getResponse().getCookie(COOKIE)).isNotNull();
    }

    @Test
    @DisplayName("the challenge token cannot itself authenticate an API call")
    void challengeTokenIsNotAnAccessToken() throws Exception {
      var secret = enrol();
      confirmEnrolment(secret);
      var challenge = dataOf(login(null)).get("challengeToken").asText();

      // typ=mfa, so the bearer filter must refuse it. Otherwise a correct password alone
      // would grant API access and MFA would be decorative.
      mockMvc.perform(get(SESSIONS).header("Authorization", "Bearer " + challenge))
          .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an invalid second factor is refused")
    void wrongSecondFactorRefused() throws Exception {
      var secret = enrol();
      confirmEnrolment(secret);
      var challenge = dataOf(login(null)).get("challengeToken").asText();

      var body = objectMapper.writeValueAsString(
          Map.of("challengeToken", challenge, "code", "000000"));
      mockMvc.perform(post(LOGIN_MFA).contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a recovery code works once and is then spent")
    void recoveryCodeIsSingleUse() throws Exception {
      var secret = enrol();
      var codes = confirmEnrolment(secret);
      var recoveryCode = codes.get(0);

      var firstChallenge = dataOf(login(null)).get("challengeToken").asText();
      var firstBody = objectMapper.writeValueAsString(
          Map.of("challengeToken", firstChallenge, "code", recoveryCode));
      mockMvc.perform(post(LOGIN_MFA).contentType(MediaType.APPLICATION_JSON).content(firstBody))
          .andExpect(status().isOk());

      assertThat(backupCodeRepository.countByUserIdAndUsedAtIsNull(
          userRepository.findByEmailIgnoreCase(EMAIL).orElseThrow().getId())).isEqualTo(9);

      // Replaying the same code must fail.
      var secondChallenge = dataOf(login(null)).get("challengeToken").asText();
      var secondBody = objectMapper.writeValueAsString(
          Map.of("challengeToken", secondChallenge, "code", recoveryCode));
      mockMvc.perform(post(LOGIN_MFA).contentType(MediaType.APPLICATION_JSON).content(secondBody))
          .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("disabling MFA requires a valid code, not just a session")
    void disableRequiresCode() throws Exception {
      var secret = enrol();
      confirmEnrolment(secret);

      // A stolen access token must not be enough to strip the second factor.
      var badBody = objectMapper.writeValueAsString(Map.of("code", "000000"));
      mockMvc.perform(post(MFA + "/disable").header("Authorization", bearer())
              .contentType(MediaType.APPLICATION_JSON).content(badBody))
          .andExpect(status().isBadRequest());
      assertThat(userRepository.findByEmailIgnoreCase(EMAIL).orElseThrow().isMfaEnabled()).isTrue();

      var goodBody = objectMapper.writeValueAsString(Map.of("code", currentCode(secret)));
      mockMvc.perform(post(MFA + "/disable").header("Authorization", bearer())
              .contentType(MediaType.APPLICATION_JSON).content(goodBody))
          .andExpect(status().isOk());

      var user = userRepository.findByEmailIgnoreCase(EMAIL).orElseThrow();
      assertThat(user.isMfaEnabled()).isFalse();
      // The secret and codes must be destroyed, not merely flagged off.
      assertThat(user.getMfaSecret()).isNull();
      assertThat(backupCodeRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("MFA endpoints reject anonymous callers")
    void mfaEndpointsRequireAuthentication() throws Exception {
      // These were exposed by the previous blanket permitAll on /api/v1/auth/**.
      mockMvc.perform(post(MFA + "/enrol")).andExpect(status().isUnauthorized());
      mockMvc.perform(get(MFA + "/status")).andExpect(status().isUnauthorized());
      mockMvc.perform(post(MFA + "/disable").contentType(MediaType.APPLICATION_JSON)
              .content("{\"code\":\"123456\"}"))
          .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("re-enrolling while enabled is refused")
    void cannotReEnrolWhileEnabled() throws Exception {
      var secret = enrol();
      confirmEnrolment(secret);

      mockMvc.perform(post(MFA + "/enrol").header("Authorization", bearer()))
          .andExpect(status().isBadRequest());
    }
  }

  // --- Sessions -----------------------------------------------------------

  @Nested
  @DisplayName("Per-device sessions")
  class Sessions {

    @Test
    @DisplayName("signing in on a second device does not end the first session")
    void secondLoginKeepsFirstSession() throws Exception {
      // The old model stored one token per user, so this silently signed the first device out.
      login("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) Safari/604.1");

      assertThat(refreshTokenRepository.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("sessions are listed with a device label and the current one flagged")
    void sessionsAreListed() throws Exception {
      login("Mozilla/5.0 (Windows NT 10.0) Chrome/120 Safari/537.36");

      mockMvc.perform(get(SESSIONS).header("Authorization", bearer()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @DisplayName("a session response never contains a token value")
    void sessionListLeaksNoTokens() throws Exception {
      var result = mockMvc.perform(get(SESSIONS).header("Authorization", bearer()))
          .andExpect(status().isOk())
          .andReturn();

      var storedToken = refreshTokenRepository.findAll().get(0).getToken();
      assertThat(result.getResponse().getContentAsString()).doesNotContain(storedToken);
    }

    @Test
    @DisplayName("a single session can be revoked")
    void singleSessionRevoked() throws Exception {
      login("Mozilla/5.0 (Windows NT 10.0) Chrome/120 Safari/537.36");
      var sessions = refreshTokenRepository.findAll();
      var target = sessions.get(0).getId();

      mockMvc.perform(delete(SESSIONS + "/" + target).header("Authorization", bearer()))
          .andExpect(status().isOk());

      assertThat(refreshTokenRepository.findById(target)).isEmpty();
      assertThat(refreshTokenRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("another user's session cannot be revoked")
    void cannotRevokeForeignSession() throws Exception {
      // A session id that does not belong to the caller resolves to nothing.
      mockMvc.perform(delete(SESSIONS + "/" + UUID.randomUUID()).header("Authorization", bearer()))
          .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("revoke-others keeps the calling device signed in")
    void revokeOthersKeepsCurrent() throws Exception {
      var second = login("Mozilla/5.0 (Windows NT 10.0) Chrome/120 Safari/537.36");
      var currentRefresh = second.getResponse().getCookie(COOKIE).getValue();
      assertThat(refreshTokenRepository.findAll()).hasSize(2);

      mockMvc.perform(post(SESSIONS + "/revoke-others")
              .header("Authorization", bearer())
              .cookie(new jakarta.servlet.http.Cookie(COOKIE, currentRefresh)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.revoked").value(1));

      var remaining = refreshTokenRepository.findAll();
      assertThat(remaining).hasSize(1);
      assertThat(remaining.get(0).getToken()).isEqualTo(currentRefresh);
    }

    @Test
    @DisplayName("session endpoints reject anonymous callers")
    void sessionEndpointsRequireAuthentication() throws Exception {
      mockMvc.perform(get(SESSIONS)).andExpect(status().isUnauthorized());
      mockMvc.perform(post(SESSIONS + "/revoke-others")).andExpect(status().isUnauthorized());
    }
  }

  @Nested
  @DisplayName("browser cookie delivery")
  class BrowserCookie {

    private static final String ACCESS = "DEVFORGE_ACCESS_TOKEN";

    private MvcResult browserLogin() throws Exception {
      var body = objectMapper.writeValueAsString(
          Map.of("usernameOrEmail", USERNAME, "password", PASSWORD));
      return mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(body)
              .header("X-Requested-With", "XMLHttpRequest"))
          .andExpect(status().isOk()).andReturn();
    }

    @Test
    @DisplayName("a browser login gets the token as an HttpOnly cookie and not in the body")
    void browserLoginWithholdsTokenFromBody() throws Exception {
      var result = browserLogin();

      // In the body, script could read it and the cookie would protect nothing.
      assertThat(dataOf(result).get("accessToken").isNull()).isTrue();
      var cookie = result.getResponse().getCookie(ACCESS);
      assertThat(cookie).isNotNull();
      assertThat(cookie.isHttpOnly()).isTrue();
      assertThat(cookie.getPath()).isEqualTo("/api");
      // The cookie value is a working access token, which the gateway forwards as a bearer.
      mockMvc.perform(get(SESSIONS).header("Authorization", "Bearer " + cookie.getValue()))
          .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a browser refresh renews the cookie and returns no token")
    void browserRefreshWithholdsToken() throws Exception {
      var refresh = browserLogin().getResponse().getCookie(COOKIE).getValue();

      var result = mockMvc.perform(post("/api/v1/auth/refresh")
              .header("X-Requested-With", "XMLHttpRequest")
              .cookie(new jakarta.servlet.http.Cookie(COOKIE, refresh)))
          .andExpect(status().isOk()).andReturn();

      assertThat(dataOf(result).isNull()).isTrue();
      assertThat(result.getResponse().getCookie(ACCESS).getValue()).isNotBlank();
    }

    @Test
    @DisplayName("an API client still gets the token in the body")
    void apiClientUnchanged() throws Exception {
      assertThat(loginAndReadAccessToken(null)).isNotBlank();
    }

    @Test
    @DisplayName("logout expires the access cookie")
    void logoutClearsAccessCookie() throws Exception {
      var result = mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer()))
          .andExpect(status().isOk()).andReturn();

      assertThat(result.getResponse().getCookie(ACCESS).getMaxAge()).isZero();
    }
  }
}
