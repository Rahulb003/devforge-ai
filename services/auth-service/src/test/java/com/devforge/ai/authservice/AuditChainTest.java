package com.devforge.ai.authservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devforge.ai.authservice.repository.EmailVerificationTokenRepository;
import com.devforge.ai.authservice.service.AuditChainVerifier;
import com.devforge.ai.authservice.service.AuditService;
import com.devforge.ai.authservice.service.EmailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The account audit log as a hash chain: intact, tampered with, truncated, and anchored. */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Account audit chain")
class AuditChainTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ApplicationContext applicationContext;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuditService auditService;
  @Autowired private AuditChainVerifier verifier;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailVerificationTokenRepository verificationTokens;

  @MockitoBean private EmailService emailService;

  @BeforeEach
  void setUp() {
    AuthTestData.clear(applicationContext);
  }

  private void write(int count) {
    for (int i = 0; i < count; i++) {
      auditService.record(null, "TEST_ACTION", "203.0.113." + i, "entry " + i);
    }
  }

  @Test
  @DisplayName("entries written through the audit service form an intact chain")
  void intact() {
    write(4);
    var result = verifier.verify();
    assertThat(result.intact()).isTrue();
    assertThat(result.entries()).isEqualTo(4);
    assertThat(result.headHash()).matches("[0-9a-f]{64}");
  }

  @Test
  @DisplayName("an edited entry, and entries deleted from the end, are both found")
  void tamperingIsFound() {
    write(3);
    jdbc.update("UPDATE audit_logs SET details = 'rewritten' WHERE chain_sequence = 2");
    var edited = verifier.verify();
    assertThat(edited.intact()).isFalse();
    assertThat(edited.brokenAtSequence()).isEqualTo(2);
    assertThat(edited.problem()).isEqualTo("its content does not match its hash");

    AuthTestData.clear(applicationContext);
    write(3);
    jdbc.update("DELETE FROM audit_logs WHERE chain_sequence = 3");
    var truncated = verifier.verify();
    assertThat(truncated.intact()).isFalse();
    assertThat(truncated.entries()).isEqualTo(2);
  }

  @Test
  @DisplayName("the head is written to the log stream once per change")
  void anchored(CapturedOutput output) {
    write(2);
    assertThat(verifier.anchorIfMoved()).isTrue();
    assertThat(output.getOut()).contains(
        "audit-chain-anchor chain=accounts sequence=2 head=" + verifier.verify().headHash());
    assertThat(verifier.anchorIfMoved()).isFalse();
  }

  @Test
  @DisplayName("verification is for platform administrators only")
  void adminsOnly() throws Exception {
    var body = objectMapper.writeValueAsString(Map.of(
        "firstName", "Reg", "lastName", "User", "username", "regular",
        "email", "regular@example.com", "password", "Str0ng-Passw0rd!", "organization", "DevForge"));
    mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated());
    mockMvc.perform(post("/api/v1/auth/verify-email")
            .param("token", verificationTokens.findAll().get(0).getToken()))
        .andExpect(status().isOk());
    var login = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"regular\",\"password\":\"Str0ng-Passw0rd!\"}"))
        .andExpect(status().isOk()).andReturn();
    var token = objectMapper.readTree(login.getResponse().getContentAsString())
        .path("data").path("accessToken").asText();

    mockMvc.perform(get("/api/v1/auth/audit/verification").header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
    mockMvc.perform(get("/api/v1/auth/audit/verification")).andExpect(status().isUnauthorized());
  }
}
