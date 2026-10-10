package com.devforge.ai.authservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devforge.ai.authservice.client.OrganizationMembershipClient;
import com.devforge.ai.authservice.model.AccountStatus;
import com.devforge.ai.authservice.repository.AuditLogRepository;
import com.devforge.ai.authservice.repository.EmailVerificationTokenRepository;
import com.devforge.ai.authservice.repository.LoginHistoryRepository;
import com.devforge.ai.authservice.repository.RefreshTokenRepository;
import com.devforge.ai.authservice.repository.UserRepository;
import com.devforge.ai.authservice.service.EmailService;
import com.devforge.ai.common.events.outbox.OutboxEventRepository;
import com.devforge.ai.common.exception.ResourceConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * Deleting your own account: confirmed by the owner, refused while it would orphan an
 * organization, and leaving no personal data or working credential behind.
 *
 * <p>project-service is replaced at the network boundary; its side - leaving every organization,
 * refused for a sole owner - is tested in project-service's MembershipTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Account deletion")
class AccountDeletionTest {

  private static final String PASSWORD = "Str0ng-Passw0rd!";

  @Autowired private MockMvc mockMvc;
  @Autowired private ApplicationContext applicationContext;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository users;
  @Autowired private EmailVerificationTokenRepository verificationTokens;
  @Autowired private RefreshTokenRepository refreshTokens;
  @Autowired private LoginHistoryRepository loginHistory;
  @Autowired private AuditLogRepository auditLogs;
  @Autowired private OutboxEventRepository outbox;

  @MockitoBean private EmailService emailService;
  @MockitoBean private OrganizationMembershipClient memberships;

  private String bearer;

  @BeforeEach
  void setUp() throws Exception {
    AuthTestData.clear(applicationContext);
    outbox.deleteAll();
    mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "firstName", "Ada", "lastName", "Lovelace", "username", "ada",
                "email", "ada@example.com", "password", PASSWORD, "organization", "DevForge"))))
        .andExpect(status().isCreated());
    mockMvc.perform(post("/api/v1/auth/verify-email")
            .param("token", verificationTokens.findAll().get(0).getToken()))
        .andExpect(status().isOk());
    bearer = "Bearer " + objectMapper.readTree(login(PASSWORD).andReturn().getResponse().getContentAsString())
        .path("data").path("accessToken").asText();
  }

  private ResultActions login(String password) throws Exception {
    return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(Map.of("usernameOrEmail", "ada", "password", password))));
  }

  private ResultActions deleteAccount(String username, String password) throws Exception {
    var body = new HashMap<String, Object>();
    body.put("username", username);
    body.put("password", password);
    return mockMvc.perform(delete("/api/v1/auth/me").header("Authorization", bearer)
        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
  }

  @Test
  @DisplayName("erases the personal data, ends every session and refuses to sign in again")
  void deletes() throws Exception {
    when(memberships.leaveAllOrganizations(anyString())).thenReturn(1);
    var id = users.findByUsernameIgnoreCase("ada").orElseThrow().getId();

    deleteAccount("Ada", PASSWORD).andExpect(status().isOk());

    verify(memberships).leaveAllOrganizations(bearer);
    var user = users.findById(id).orElseThrow();
    assertThat(user.getStatus()).isEqualTo(AccountStatus.DELETED);
    assertThat(user.getUsername()).startsWith("deleted-").doesNotContain("ada");
    assertThat(user.getEmail()).endsWith("@deleted.invalid").doesNotContain("ada@example.com");
    assertThat(user.getFirstName()).isEqualTo("Deleted");
    assertThat(user.getPasswordHash()).isNull();
    assertThat(user.getOrganization()).isNull();
    assertThat(refreshTokens.findByUser(user)).isEmpty();
    assertThat(loginHistory.findAll()).noneMatch(h -> h.getUser().getId().equals(id));
    assertThat(users.findByEmailIgnoreCase("ada@example.com")).isEmpty();

    login(PASSWORD).andExpect(status().is4xxClientError());
    assertThat(auditLogs.findAll()).anyMatch(a -> a.getAction().equals("ACCOUNT_DELETED"));
    assertThat(outbox.findAll()).anyMatch(e -> e.getEventType().equals("UserDeleted")
        && !e.getPayload().contains("ada"));
  }

  @Test
  @DisplayName("the address and username are free for someone to sign up with afterwards")
  void identifiersAreReleased() throws Exception {
    when(memberships.leaveAllOrganizations(anyString())).thenReturn(0);
    deleteAccount("ada", PASSWORD).andExpect(status().isOk());

    mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "firstName", "Ada", "lastName", "Byron", "username", "ada",
                "email", "ada@example.com", "password", PASSWORD, "organization", "Elsewhere"))))
        .andExpect(status().isCreated());
  }

  @Test
  @DisplayName("needs the username typed out and the password, and asks project-service nothing otherwise")
  void needsConfirmation() throws Exception {
    deleteAccount("not-ada", PASSWORD).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Type your username to confirm"));
    deleteAccount("ada", "Wr0ng-Passw0rd!").andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Password is incorrect"));

    verify(memberships, never()).leaveAllOrganizations(anyString());
    assertThat(users.findByUsernameIgnoreCase("ada").orElseThrow().getStatus()).isEqualTo(AccountStatus.ACTIVE);
  }

  @Test
  @DisplayName("a sole owner is refused with project-service's reason, and nothing is erased")
  void soleOwnerIsRefused() throws Exception {
    when(memberships.leaveAllOrganizations(anyString())).thenThrow(new ResourceConflictException(
        "You are the only owner of Acme. Make someone else an owner, or delete the organization, first."));

    deleteAccount("ada", PASSWORD).andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Acme")));

    var user = users.findByUsernameIgnoreCase("ada").orElseThrow();
    assertThat(user.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    login(PASSWORD).andExpect(status().isOk());
  }

  @Test
  @DisplayName("an unreachable project-service is a 503, and nothing is erased")
  void dependencyDownFailsClosed() throws Exception {
    when(memberships.leaveAllOrganizations(anyString())).thenThrow(
        new OrganizationMembershipClient.DependencyUnavailableException("Your account cannot be deleted right now."));

    deleteAccount("ada", PASSWORD).andExpect(status().isServiceUnavailable());
    assertThat(users.findByUsernameIgnoreCase("ada").orElseThrow().getStatus()).isEqualTo(AccountStatus.ACTIVE);
  }
}
