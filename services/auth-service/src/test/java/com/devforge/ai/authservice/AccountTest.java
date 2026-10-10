package com.devforge.ai.authservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devforge.ai.authservice.repository.AuditLogRepository;
import com.devforge.ai.authservice.repository.EmailVerificationTokenRepository;
import com.devforge.ai.authservice.repository.RefreshTokenRepository;
import com.devforge.ai.authservice.repository.UserRepository;
import com.devforge.ai.authservice.service.EmailService;
import com.devforge.ai.common.events.outbox.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** The signed-in user editing their profile and changing their password. */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Account changes")
class AccountTest {

  private static final String PASSWORD = "Str0ng-Passw0rd!";
  private static final String NEW_PASSWORD = "Ev3n-Str0nger-Pass!";
  private static final String COOKIE = "DEVFORGE_REFRESH_TOKEN";

  @Autowired private MockMvc mockMvc;
  @Autowired private ApplicationContext applicationContext;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository users;
  @Autowired private EmailVerificationTokenRepository verificationTokens;
  @Autowired private RefreshTokenRepository refreshTokens;
  @Autowired private AuditLogRepository auditLogs;
  @Autowired private OutboxEventRepository outbox;

  @MockitoBean private EmailService emailService;

  @BeforeEach
  void setUp() throws Exception {
    AuthTestData.clear(applicationContext);
    outbox.deleteAll();
    var body = objectMapper.writeValueAsString(Map.of(
        "firstName", "Ada", "lastName", "Lovelace", "username", "ada",
        "email", "ada@example.com", "password", PASSWORD, "organization", "DevForge"));
    mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated());
    mockMvc.perform(post("/api/v1/auth/verify-email")
            .param("token", verificationTokens.findAll().get(0).getToken()))
        .andExpect(status().isOk());
  }

  private MvcResult login(String password) throws Exception {
    return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("usernameOrEmail", "ada", "password", password))))
        .andReturn();
  }

  private String bearer(MvcResult login) throws Exception {
    return "Bearer " + objectMapper.readTree(login.getResponse().getContentAsString())
        .path("data").path("accessToken").asText();
  }

  private ResultActions changePassword(MvcResult session, String current, String next) throws Exception {
    return mockMvc.perform(post("/api/v1/auth/password")
        .header("Authorization", bearer(session))
        .cookie(session.getResponse().getCookie(COOKIE))
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(Map.of("currentPassword", current, "newPassword", next))));
  }

  private ResultActions updateProfile(String bearer, Map<String, Object> changes) throws Exception {
    return mockMvc.perform(patch("/api/v1/auth/me").header("Authorization", bearer)
        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(changes)));
  }

  @Test
  @DisplayName("the profile changes only the fields given, and /me shows them")
  void profileUpdate() throws Exception {
    var bearer = bearer(login(PASSWORD));
    updateProfile(bearer, Map.of("firstName", "  Augusta Ada ", "timezone", "Europe/London", "language", "en-GB"))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/v1/auth/me").header("Authorization", bearer))
        .andExpect(jsonPath("$.data.firstName").value("Augusta Ada"))
        .andExpect(jsonPath("$.data.lastName").value("Lovelace"))
        .andExpect(jsonPath("$.data.timezone").value("Europe/London"))
        .andExpect(jsonPath("$.data.language").value("en-GB"))
        // Not editable here, whatever the body says.
        .andExpect(jsonPath("$.data.username").value("ada"));
    assertThat(auditLogs.findAll()).anyMatch(a -> a.getAction().equals("PROFILE_UPDATED"));
  }

  @Test
  @DisplayName("invalid profile values are refused")
  void profileValidation() throws Exception {
    var bearer = bearer(login(PASSWORD));
    updateProfile(bearer, Map.of("timezone", "Mars/Olympus")).andExpect(status().isBadRequest());
    updateProfile(bearer, Map.of("language", "english")).andExpect(status().isBadRequest());
    updateProfile(bearer, Map.of("firstName", "   ")).andExpect(status().isBadRequest());
    updateProfile(bearer, Map.of("lastName", "x\u0000y")).andExpect(status().isBadRequest());
    mockMvc.perform(patch("/api/v1/auth/me").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("a password change needs the current password, signs out other devices, and tells the owner")
  void passwordChange() throws Exception {
    var here = login(PASSWORD);
    var elsewhere = login(PASSWORD);
    assertThat(refreshTokens.findAll()).hasSize(2);

    changePassword(here, PASSWORD, NEW_PASSWORD)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.otherSessionsSignedOut").value(1));

    assertThat(login(NEW_PASSWORD).getResponse().getStatus()).isEqualTo(200);
    assertThat(login(PASSWORD).getResponse().getStatus()).isEqualTo(401);
    // This device's session is kept; the other one is gone. Checked in the store rather than by
    // presenting the old token: a dead refresh token is treated as stolen and ends every session.
    mockMvc.perform(post("/api/v1/auth/refresh").cookie(here.getResponse().getCookie(COOKIE)))
        .andExpect(status().isOk());
    var elsewhereToken = elsewhere.getResponse().getCookie(COOKIE).getValue();
    assertThat(refreshTokens.findAll()).noneMatch(t -> t.getToken().equals(elsewhereToken));

    assertThat(auditLogs.findAll()).anyMatch(a -> a.getAction().equals("PASSWORD_CHANGED"));
    assertThat(outbox.findAll()).anyMatch(e -> e.getEventType().equals("UserPasswordReset"));
  }

  @Test
  @DisplayName("a wrong current password, a weak one, or the same one is refused")
  void passwordRefusals() throws Exception {
    var session = login(PASSWORD);
    changePassword(session, "Wrong-Passw0rd!!", NEW_PASSWORD).andExpect(status().isBadRequest());
    changePassword(session, PASSWORD, "short").andExpect(status().isBadRequest());
    changePassword(session, PASSWORD, "alllowercaseletters1!").andExpect(status().isBadRequest());
    changePassword(session, PASSWORD, PASSWORD).andExpect(status().isBadRequest());
    // Nothing changed.
    assertThat(login(PASSWORD).getResponse().getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("guessing the current password trips the same lockout as signing in")
  void guessingLocksOut() throws Exception {
    var session = login(PASSWORD);
    for (int i = 0; i < 5; i++) {
      changePassword(session, "Guess-Number-" + i + "!", NEW_PASSWORD).andExpect(status().isBadRequest());
    }
    // Now even the right password is refused until the window passes.
    changePassword(session, PASSWORD, NEW_PASSWORD)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Too many failed attempts. Try again later."));
    assertThat(users.findByUsernameIgnoreCase("ada")).isPresent();
  }

  @SuppressWarnings("unused")
  private static Cookie none() {
    return null;
  }
}
