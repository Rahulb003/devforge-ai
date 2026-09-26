package com.devforge.ai.authservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devforge.ai.authservice.model.AccountStatus;
import com.devforge.ai.authservice.repository.AuditLogRepository;
import com.devforge.ai.authservice.repository.EmailVerificationTokenRepository;
import com.devforge.ai.authservice.repository.LoginHistoryRepository;
import com.devforge.ai.authservice.repository.RefreshTokenRepository;
import com.devforge.ai.authservice.repository.UserRepository;
import com.devforge.ai.authservice.service.EmailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Signup with {@code devforge.auth.require-email-verification=false}.
 *
 * <p>This is the configuration the standalone and local profiles use, so a new account can be used
 * immediately. The trade-off is deliberate and recorded on the flag: nothing proves the registrant
 * controls the address, and the address is the account-recovery channel. The production default
 * remains on, which {@code AuthFlowIntegrationTest} covers.
 */
@SpringBootTest(properties = "devforge.auth.require-email-verification=false")
@AutoConfigureMockMvc
class SignupWithoutVerificationTest {

  private static final String SIGNUP = "/api/v1/auth/signup";
  private static final String LOGIN = "/api/v1/auth/login";
  private static final String EMAIL = "instant@example.com";
  private static final String USERNAME = "instant";
  private static final String PASSWORD = "Str0ng-Passw0rd!";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private RefreshTokenRepository refreshTokenRepository;
  @Autowired private EmailVerificationTokenRepository emailVerificationTokenRepository;
  @Autowired private LoginHistoryRepository loginHistoryRepository;
  @Autowired private AuditLogRepository auditLogRepository;

  @MockitoBean private EmailService emailService;

  @BeforeEach
  void setUp() {
    loginHistoryRepository.deleteAll();
    auditLogRepository.deleteAll();
    refreshTokenRepository.deleteAll();
    emailVerificationTokenRepository.deleteAll();
    userRepository.deleteAll();
  }

  private void signup() throws Exception {
    var body = objectMapper.writeValueAsString(Map.of(
        "firstName", "Instant", "lastName", "User", "username", USERNAME,
        "email", EMAIL, "password", PASSWORD, "organization", "DevForge"));
    mockMvc.perform(post(SIGNUP).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated());
  }

  @Test
  @DisplayName("a new account is active immediately")
  void accountIsActiveOnCreation() throws Exception {
    signup();

    var user = userRepository.findByEmailIgnoreCase(EMAIL).orElseThrow();
    assertThat(user.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    assertThat(user.isEmailVerified()).isTrue();
  }

  @Test
  @DisplayName("the account can sign in without clicking a verification link")
  void loginSucceedsImmediately() throws Exception {
    signup();

    var body = objectMapper.writeValueAsString(
        Map.of("usernameOrEmail", USERNAME, "password", PASSWORD));
    mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
        .andExpect(jsonPath("$.data.mfaRequired").value(false));
  }

  @Test
  @DisplayName("no verification email is produced, because there is nothing to verify")
  void noVerificationMailIsSent() throws Exception {
    signup();

    // Sending a verification link for an account that is already active would only
    // invite the user to click something that changes nothing.
    Mockito.verify(emailService, Mockito.never())
        .sendEmailVerification(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
    assertThat(emailVerificationTokenRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName("a welcome email is produced instead")
  void welcomeMailIsSent() throws Exception {
    signup();

    Mockito.verify(emailService).sendWelcomeEmail(ArgumentMatchers.eq(EMAIL));
  }

  @Test
  @DisplayName("the password is still hashed")
  void passwordIsStillHashed() throws Exception {
    signup();

    // Relaxing the verification gate must not relax anything else about the account.
    var user = userRepository.findByEmailIgnoreCase(EMAIL).orElseThrow();
    assertThat(user.getPasswordHash()).isNotEqualTo(PASSWORD).startsWith("$2");
  }

  @Test
  @DisplayName("a duplicate email is still rejected")
  void duplicateEmailStillRejected() throws Exception {
    signup();

    var body = objectMapper.writeValueAsString(Map.of(
        "firstName", "Instant", "lastName", "User", "username", "different",
        "email", EMAIL, "password", PASSWORD, "organization", "DevForge"));
    mockMvc.perform(post(SIGNUP).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isConflict());
  }
}
