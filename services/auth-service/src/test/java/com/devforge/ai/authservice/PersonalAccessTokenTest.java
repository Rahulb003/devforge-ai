package com.devforge.ai.authservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devforge.ai.authservice.model.AccountStatus;
import com.devforge.ai.authservice.repository.AuditLogRepository;
import com.devforge.ai.authservice.repository.EmailVerificationTokenRepository;
import com.devforge.ai.authservice.repository.PersonalAccessTokenRepository;
import com.devforge.ai.authservice.repository.UserRepository;
import com.devforge.ai.authservice.security.JwtTokenProvider;
import com.devforge.ai.authservice.service.EmailService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
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

/** Personal access tokens: issued once, stored hashed, exchanged only while usable. */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Personal access tokens")
class PersonalAccessTokenTest {

  private static final String TOKENS = "/api/v1/auth/tokens";
  private static final String EXCHANGE = "/internal/v1/tokens/exchange";
  private static final String PASSWORD = "Str0ng-Passw0rd!";

  @Autowired private MockMvc mockMvc;
  @Autowired private ApplicationContext applicationContext;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository users;
  @Autowired private PersonalAccessTokenRepository tokens;
  @Autowired private AuditLogRepository auditLogs;
  @Autowired private EmailVerificationTokenRepository verificationTokens;
  @Autowired private JwtTokenProvider jwtTokenProvider;

  @MockitoBean private EmailService emailService;

  private String ada;
  private String grace;

  @BeforeEach
  void setUp() throws Exception {
    AuthTestData.clear(applicationContext);
    ada = signUpAndLogIn("ada");
    grace = signUpAndLogIn("grace");
  }

  private String signUpAndLogIn(String username) throws Exception {
    var body = objectMapper.writeValueAsString(Map.of(
        "firstName", "Test", "lastName", "User", "username", username,
        "email", username + "@example.com", "password", PASSWORD, "organization", "DevForge"));
    mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated());
    var user = users.findByUsernameIgnoreCase(username).orElseThrow();
    var verification = verificationTokens.findAll().stream()
        .filter(t -> t.getUser().getId().equals(user.getId())).findFirst().orElseThrow();
    mockMvc.perform(post("/api/v1/auth/verify-email").param("token", verification.getToken()))
        .andExpect(status().isOk());
    var login = objectMapper.writeValueAsString(Map.of("usernameOrEmail", username, "password", PASSWORD));
    var result = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON).content(login))
        .andExpect(status().isOk()).andReturn();
    return "Bearer " + data(result.getResponse().getContentAsString()).get("accessToken").asText();
  }

  private JsonNode data(String body) throws Exception {
    return objectMapper.readTree(body).get("data");
  }

  private ResultActions create(String bearer, Map<String, Object> request) throws Exception {
    return mockMvc.perform(post(TOKENS).header("Authorization", bearer)
        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)));
  }

  /** Creates a token and returns {id, token}. */
  private String[] issue(String bearer, String name) throws Exception {
    var body = create(bearer, Map.of("name", name, "expiresInDays", 30))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    var created = data(body);
    return new String[] {created.get("details").get("id").asText(), created.get("token").asText()};
  }

  private ResultActions exchange(String token) throws Exception {
    return mockMvc.perform(post(EXCHANGE).contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(Map.of("token", token))));
  }

  @Test
  @DisplayName("the token is shown once, and only its hash is stored")
  void tokenIsShownOnce() throws Exception {
    var token = issue(ada, "laptop")[1];
    assertThat(token).startsWith("dfp_").hasSize(47);

    var listed = mockMvc.perform(get(TOKENS).header("Authorization", ada))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].name").value("laptop"))
        .andExpect(jsonPath("$.data[0].prefix").value(token.substring(0, 12)))
        .andReturn().getResponse().getContentAsString();
    assertThat(listed).doesNotContain(token).doesNotContain("tokenHash");

    var stored = tokens.findAll().get(0);
    assertThat(stored.getTokenHash()).hasSize(64).isNotEqualTo(token);
    assertThat(auditLogs.findAll()).anyMatch(a -> a.getAction().equals("ACCESS_TOKEN_CREATED"));
    // The audit row names the token but never carries it.
    assertThat(auditLogs.findAll()).noneMatch(a -> a.getDetails() != null && a.getDetails().contains(token));
  }

  @Test
  @DisplayName("a live token exchanges for a five-minute access token for its owner")
  void exchangeIssuesShortLivedAccessToken() throws Exception {
    var token = issue(ada, "ci")[1];

    var body = exchange(token).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    var accessToken = objectMapper.readTree(body).get("accessToken").asText();

    var claims = jwtTokenProvider.parseClaims(accessToken);
    assertThat(claims.getSubject()).isEqualTo(users.findByUsernameIgnoreCase("ada").orElseThrow().getId().toString());
    assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime()).isEqualTo(300_000);
    mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.username").value("ada"));
    assertThat(tokens.findAll().get(0).getLastUsedAt()).isNotNull();
  }

  @Test
  @DisplayName("a revoked token no longer exchanges, and only its owner can revoke it")
  void revocation() throws Exception {
    var issued = issue(ada, "old laptop");

    // Another account sees no such token: 404, not 403.
    mockMvc.perform(delete(TOKENS + "/" + issued[0]).header("Authorization", grace))
        .andExpect(status().isNotFound());
    exchange(issued[1]).andExpect(status().isOk());

    mockMvc.perform(delete(TOKENS + "/" + issued[0]).header("Authorization", ada))
        .andExpect(status().isOk());
    exchange(issued[1]).andExpect(status().isUnauthorized());
    mockMvc.perform(get(TOKENS).header("Authorization", ada))
        .andExpect(jsonPath("$.data.length()").value(0));
    // Revoking twice is a missing token, not a second success.
    mockMvc.perform(delete(TOKENS + "/" + issued[0]).header("Authorization", ada))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("an exchange that came through a proxy is refused, even with a live token")
  void proxiedExchangeIsRefused() throws Exception {
    var token = issue(ada, "laptop")[1];
    var body = objectMapper.writeValueAsString(Map.of("token", token));

    // What nginx and the gateway add. If forward-headers handling were ever switched on, Spring
    // would strip these before the controller saw them, and this test is what would notice.
    for (var header : new String[] {"X-Forwarded-For", "Forwarded", "X-Forwarded-Host"}) {
      mockMvc.perform(post(EXCHANGE).header(header, "203.0.113.7")
              .contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isNotFound());
    }
    exchange(token).andExpect(status().isOk());
  }

  @Test
  @DisplayName("an expired token does not exchange")
  void expiredToken() throws Exception {
    var token = issue(ada, "short")[1];
    var stored = tokens.findAll().get(0);
    // expires_at is not updatable through the entity, which is the point; the test goes around it.
    tokens.delete(stored);
    stored.setExpiresAt(Instant.now().minusSeconds(1));
    tokens.saveAndFlush(stored);

    exchange(token).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("locking the account stops its tokens at once")
  void lockedAccount() throws Exception {
    var token = issue(ada, "laptop")[1];
    var user = users.findByUsernameIgnoreCase("ada").orElseThrow();
    user.setStatus(AccountStatus.LOCKED);
    users.save(user);

    exchange(token).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("anything that is not a live token is refused the same way")
  void garbageIsRefused() throws Exception {
    exchange("dfp_" + "A".repeat(43)).andExpect(status().isUnauthorized());
    exchange("not-a-token").andExpect(status().isUnauthorized());
    exchange("").andExpect(status().isUnauthorized());
    // An access token is not a personal token.
    exchange(ada.substring("Bearer ".length())).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("names and lifetimes are validated, and tokens need a signed-in owner")
  void validation() throws Exception {
    create(ada, Map.of("name", "  ")).andExpect(status().isBadRequest());
    create(ada, Map.of("name", "bad\u0000name")).andExpect(status().isBadRequest());
    create(ada, Map.of("name", "x".repeat(101))).andExpect(status().isBadRequest());
    create(ada, Map.of("name", "ok", "expiresInDays", 0)).andExpect(status().isBadRequest());
    create(ada, Map.of("name", "ok", "expiresInDays", 366)).andExpect(status().isBadRequest());

    mockMvc.perform(get(TOKENS)).andExpect(status().isUnauthorized());
    mockMvc.perform(post(TOKENS).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
        .andExpect(status().isUnauthorized());
  }
}
