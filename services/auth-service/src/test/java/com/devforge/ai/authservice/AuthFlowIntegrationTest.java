package com.devforge.ai.authservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devforge.ai.authservice.model.AccountStatus;
import com.devforge.ai.authservice.repository.AuditLogRepository;
import com.devforge.ai.authservice.repository.EmailVerificationTokenRepository;
import com.devforge.ai.authservice.repository.LoginHistoryRepository;
import com.devforge.ai.authservice.repository.PasswordResetTokenRepository;
import com.devforge.ai.authservice.repository.RefreshTokenRepository;
import com.devforge.ai.authservice.repository.UserRepository;
import com.devforge.ai.authservice.security.JwtTokenProvider;
import com.devforge.ai.authservice.service.EmailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * End-to-end authentication flow against the real Spring context, the real Flyway-migrated
 * schema (H2 in PostgreSQL mode) and the real security filter chain.
 *
 * <p>Only {@link EmailService} is mocked, because delivering mail is an external side effect.
 * Verification and reset tokens are read back from their repositories instead, which is how a
 * user would obtain them from the email body.
 */
@SpringBootTest
class AuthFlowIntegrationTest {

  private static final String SIGNUP = "/api/v1/auth/signup";
  private static final String LOGIN = "/api/v1/auth/login";
  private static final String REFRESH = "/api/v1/auth/refresh";
  private static final String LOGOUT = "/api/v1/auth/logout";
  private static final String VERIFY = "/api/v1/auth/verify-email";
  private static final String FORGOT = "/api/v1/auth/forgot-password";
  private static final String RESET = "/api/v1/auth/reset-password";

  private static final String EMAIL = "ada@example.com";
  private static final String USERNAME = "ada";
  private static final String PASSWORD = "Str0ng-Passw0rd!";

  @Autowired private WebApplicationContext context;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private RefreshTokenRepository refreshTokenRepository;
  @Autowired private EmailVerificationTokenRepository emailVerificationTokenRepository;
  @Autowired private PasswordResetTokenRepository passwordResetTokenRepository;
  @Autowired private JwtTokenProvider jwtTokenProvider;
  @Autowired private LoginHistoryRepository loginHistoryRepository;
  @Autowired private AuditLogRepository auditLogRepository;

  @MockitoBean private EmailService emailService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    // springSecurity() is required: without it the JWT filter chain is not applied and the
    // tests would pass against an unsecured application.
    mockMvc = MockMvcBuilders.webAppContextSetup(context)
        .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
            .springSecurity())
        .build();

    // Audit rows reference users, so they must go first or the user delete
    // trips the foreign key.
    loginHistoryRepository.deleteAll();
    auditLogRepository.deleteAll();
    refreshTokenRepository.deleteAll();
    emailVerificationTokenRepository.deleteAll();
    passwordResetTokenRepository.deleteAll();
    userRepository.deleteAll();
  }

  private String signupBody() throws Exception {
    return objectMapper.writeValueAsString(java.util.Map.of(
        "firstName", "Ada",
        "lastName", "Lovelace",
        "username", USERNAME,
        "email", EMAIL,
        "password", PASSWORD,
        "organization", "DevForge"));
  }

  private void signup() throws Exception {
    mockMvc.perform(post(SIGNUP).contentType(MediaType.APPLICATION_JSON).content(signupBody()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.email").value(EMAIL));
  }

  private void verifyEmail() throws Exception {
    var token = emailVerificationTokenRepository.findAll().get(0).getToken();
    mockMvc.perform(post(VERIFY).param("token", token)).andExpect(status().isOk());
  }

  /** Logs in and returns the raw access token from the response body. */
  private String login() throws Exception {
    var body = objectMapper.writeValueAsString(
        java.util.Map.of("usernameOrEmail", USERNAME, "password", PASSWORD));
    var result = mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("data").get("accessToken").asText();
  }

  @Test
  @DisplayName("signup persists a user that is pending verification and sends a verification email")
  void signupCreatesPendingUser() throws Exception {
    signup();

    var user = userRepository.findByEmailIgnoreCase(EMAIL).orElseThrow();
    assertThat(user.getStatus()).isEqualTo(AccountStatus.PENDING_VERIFICATION);
    assertThat(user.isEmailVerified()).isFalse();
    // The password must never be stored in the clear.
    assertThat(user.getPasswordHash()).isNotEqualTo(PASSWORD).startsWith("$2");

    verify(emailService).sendEmailVerification(org.mockito.ArgumentMatchers.eq(EMAIL), anyString());
  }

  @Test
  @DisplayName("a duplicate email is rejected")
  void duplicateEmailRejected() throws Exception {
    signup();

    mockMvc.perform(post(SIGNUP).contentType(MediaType.APPLICATION_JSON).content(signupBody()))
        .andExpect(status().isConflict());

    assertThat(userRepository.findAll()).hasSize(1);
  }

  @Test
  @DisplayName("login is refused until the email is verified")
  void loginRefusedBeforeVerification() throws Exception {
    signup();

    var body = objectMapper.writeValueAsString(
        java.util.Map.of("usernameOrEmail", USERNAME, "password", PASSWORD));
    mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.success").value(false));
  }

  @Test
  @DisplayName("verifying the email activates the account")
  void verificationActivatesAccount() throws Exception {
    signup();
    verifyEmail();

    var user = userRepository.findByEmailIgnoreCase(EMAIL).orElseThrow();
    assertThat(user.isEmailVerified()).isTrue();
    assertThat(user.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    assertThat(emailVerificationTokenRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName("login returns an access token and sets an HttpOnly refresh cookie")
  void loginIssuesTokens() throws Exception {
    signup();
    verifyEmail();

    var body = objectMapper.writeValueAsString(
        java.util.Map.of("usernameOrEmail", USERNAME, "password", PASSWORD));
    var result = mockMvc.perform(
            post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andReturn();

    var accessToken = objectMapper.readTree(result.getResponse().getContentAsString())
        .get("data").get("accessToken").asText();
    assertThat(jwtTokenProvider.validateToken(accessToken, JwtTokenProvider.TOKEN_TYPE_ACCESS))
        .isTrue();

    var setCookie = result.getResponse().getHeader("Set-Cookie");
    assertThat(setCookie).contains("DEVFORGE_REFRESH_TOKEN").contains("HttpOnly");

    // The refresh token must also be persisted, so it can be revoked server-side.
    assertThat(refreshTokenRepository.findAll()).hasSize(1);
  }

  @Test
  @DisplayName("a wrong password is rejected")
  void wrongPasswordRejected() throws Exception {
    signup();
    verifyEmail();

    var body = objectMapper.writeValueAsString(
        java.util.Map.of("usernameOrEmail", USERNAME, "password", "not-the-password"));
    mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("the refresh cookie exchanges for a fresh access token")
  void refreshReturnsNewAccessToken() throws Exception {
    signup();
    verifyEmail();
    login();

    var storedRefresh = refreshTokenRepository.findAll().get(0).getToken();

    var result = mockMvc.perform(
            post(REFRESH).cookie(new Cookie("DEVFORGE_REFRESH_TOKEN", storedRefresh)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andReturn();

    var newAccess = objectMapper.readTree(result.getResponse().getContentAsString())
        .get("data").asText();
    assertThat(jwtTokenProvider.validateToken(newAccess, JwtTokenProvider.TOKEN_TYPE_ACCESS))
        .isTrue();
  }

  @Test
  @DisplayName("refresh is refused without a cookie")
  void refreshRefusedWithoutCookie() throws Exception {
    mockMvc.perform(post(REFRESH)).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("an access token is not accepted as a refresh token")
  void accessTokenRejectedAtRefreshEndpoint() throws Exception {
    signup();
    verifyEmail();
    var accessToken = login();

    // Token-type confusion: presenting the short-lived access token at the refresh
    // endpoint must fail, and vice versa.
    mockMvc.perform(post(REFRESH).cookie(new Cookie("DEVFORGE_REFRESH_TOKEN", accessToken)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("a refresh token is not accepted as a bearer access token")
  void refreshTokenRejectedAsBearer() throws Exception {
    signup();
    verifyEmail();
    login();
    var storedRefresh = refreshTokenRepository.findAll().get(0).getToken();

    // Must not authenticate: a stolen 14-day refresh token would otherwise be a
    // general-purpose API credential.
    mockMvc.perform(post(LOGOUT).header("Authorization", "Bearer " + storedRefresh))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("logout revokes the persisted refresh token")
  void logoutRevokesRefreshToken() throws Exception {
    signup();
    verifyEmail();
    var accessToken = login();

    assertThat(refreshTokenRepository.findAll()).hasSize(1);

    mockMvc.perform(post(LOGOUT).header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));

    assertThat(refreshTokenRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName("logout without a token is unauthorized")
  void logoutRequiresAuthentication() throws Exception {
    mockMvc.perform(post(LOGOUT)).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("a garbage bearer token does not authenticate")
  void invalidBearerTokenRejected() throws Exception {
    mockMvc.perform(post(LOGOUT).header("Authorization", "Bearer not-a-jwt"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("forgot-password does not reveal whether an account exists")
  void forgotPasswordDoesNotEnumerateAccounts() throws Exception {
    signup();
    verifyEmail();

    var knownBody = objectMapper.writeValueAsString(java.util.Map.of("email", EMAIL));
    var unknownBody =
        objectMapper.writeValueAsString(java.util.Map.of("email", "nobody@example.com"));

    var known = mockMvc.perform(
            post(FORGOT).contentType(MediaType.APPLICATION_JSON).content(knownBody))
        .andExpect(status().isOk())
        .andReturn();

    var unknown = mockMvc.perform(
            post(FORGOT).contentType(MediaType.APPLICATION_JSON).content(unknownBody))
        .andExpect(status().isOk())
        .andReturn();

    // Identical status and body: the endpoint must not be an enumeration oracle.
    assertThat(unknown.getResponse().getContentAsString())
        .isEqualTo(known.getResponse().getContentAsString());

    // A reset token is only created for the address that actually exists.
    assertThat(passwordResetTokenRepository.findAll()).hasSize(1);
  }

  @Test
  @DisplayName("a password reset token lets the user log in with the new password")
  void passwordResetChangesCredentials() throws Exception {
    signup();
    verifyEmail();

    var forgotBody = objectMapper.writeValueAsString(java.util.Map.of("email", EMAIL));
    mockMvc.perform(post(FORGOT).contentType(MediaType.APPLICATION_JSON).content(forgotBody))
        .andExpect(status().isOk());

    var resetToken = passwordResetTokenRepository.findAll().get(0).getToken();
    var newPassword = "An0ther-Str0ng-Pass!";
    var resetBody = objectMapper.writeValueAsString(
        java.util.Map.of("token", resetToken, "newPassword", newPassword));

    mockMvc.perform(post(RESET).contentType(MediaType.APPLICATION_JSON).content(resetBody))
        .andExpect(status().isOk());

    // Old password no longer works.
    var oldLogin = objectMapper.writeValueAsString(
        java.util.Map.of("usernameOrEmail", USERNAME, "password", PASSWORD));
    mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(oldLogin))
        .andExpect(status().isUnauthorized());

    // New password does.
    var newLogin = objectMapper.writeValueAsString(
        java.util.Map.of("usernameOrEmail", USERNAME, "password", newPassword));
    mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(newLogin))
        .andExpect(status().isOk());

    // The single-use token must be consumed.
    assertThat(passwordResetTokenRepository.findAll()).isEmpty();
  }
}
