package com.devforge.ai.authservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devforge.ai.authservice.repository.EmailVerificationTokenRepository;
import com.devforge.ai.authservice.repository.RefreshTokenRepository;
import com.devforge.ai.authservice.repository.UserRepository;
import com.devforge.ai.authservice.service.EmailService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Downloading your own account data: complete enough to be useful, and never a credential. */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Account export")
class AccountExportTest {

  private static final String PASSWORD = "Str0ng-Passw0rd!";

  @Autowired private MockMvc mockMvc;
  @Autowired private ApplicationContext applicationContext;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository users;
  @Autowired private EmailVerificationTokenRepository verificationTokens;
  @Autowired private RefreshTokenRepository refreshTokens;

  @MockitoBean private EmailService emailService;

  private String bearer;

  @BeforeEach
  void setUp() throws Exception {
    AuthTestData.clear(applicationContext);
    mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "firstName", "Ada", "lastName", "Lovelace", "username", "ada",
                "email", "ada@example.com", "password", PASSWORD, "organization", "DevForge"))))
        .andExpect(status().isCreated());
    mockMvc.perform(post("/api/v1/auth/verify-email")
            .param("token", verificationTokens.findAll().get(0).getToken()))
        .andExpect(status().isOk());
    var login = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("usernameOrEmail", "ada", "password", PASSWORD))))
        .andReturn().getResponse().getContentAsString();
    bearer = "Bearer " + objectMapper.readTree(login).path("data").path("accessToken").asText();
  }

  @Test
  @DisplayName("is a download of the profile, sessions and sign-in history")
  void exportsTheAccount() throws Exception {
    mockMvc.perform(get("/api/v1/auth/me/export").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Disposition", "attachment; filename=\"devforge-account.json\""))
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.profile.username").value("ada"))
        .andExpect(jsonPath("$.profile.email").value("ada@example.com"))
        .andExpect(jsonPath("$.profile.emailVerified").value(true))
        .andExpect(jsonPath("$.sessions.length()").value(1))
        .andExpect(jsonPath("$.signInHistory[0].status").value("SUCCESS"))
        .andExpect(jsonPath("$.auditTrail").isArray());
  }

  @Test
  @DisplayName("contains no password hash, MFA secret or token value")
  void containsNoCredentials() throws Exception {
    var body = mockMvc.perform(get("/api/v1/auth/me/export").header("Authorization", bearer))
        .andReturn().getResponse().getContentAsString();
    var user = users.findByUsernameIgnoreCase("ada").orElseThrow();
    var refreshToken = refreshTokens.findByUser(user).get(0).getToken();

    assertThat(body).doesNotContain(user.getPasswordHash()).doesNotContain("$2a$").doesNotContain(refreshToken)
        .doesNotContain(PASSWORD).doesNotContainIgnoringCase("passwordHash").doesNotContainIgnoringCase("mfaSecret");
  }

  @Test
  @DisplayName("needs a signed-in caller")
  void needsAuthentication() throws Exception {
    mockMvc.perform(get("/api/v1/auth/me/export")).andExpect(status().isUnauthorized());
  }
}
