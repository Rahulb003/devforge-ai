package com.devforge.ai.authservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devforge.ai.common.events.EventTypes;
import com.devforge.ai.common.events.KafkaTopics;
import com.devforge.ai.common.events.outbox.IdempotentEventProcessor;
import com.devforge.ai.common.events.outbox.OutboxEventRecorder;
import com.devforge.ai.common.events.outbox.OutboxEventRepository;
import com.devforge.ai.common.events.outbox.ProcessedEventRepository;
import com.devforge.ai.authservice.repository.AuditLogRepository;
import com.devforge.ai.authservice.repository.EmailVerificationTokenRepository;
import com.devforge.ai.authservice.repository.LoginHistoryRepository;
import com.devforge.ai.authservice.repository.MfaBackupCodeRepository;
import com.devforge.ai.authservice.repository.PasswordResetTokenRepository;
import com.devforge.ai.authservice.repository.RefreshTokenRepository;
import com.devforge.ai.authservice.repository.UserRepository;
import com.devforge.ai.authservice.service.EmailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Covers the transactional outbox and consumer-side idempotency.
 *
 * <p>No Kafka broker is involved. What is verified here is the part that must hold regardless of
 * the broker: that events are staged atomically with the state change, that a rolled-back
 * transaction leaves no event behind, and that a duplicate is processed only once. Publishing to a
 * real broker needs Testcontainers and is recorded as UNVERIFIED in docs/PROGRESS.md.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OutboxEventTest {

  private static final String SIGNUP = "/api/v1/auth/signup";
  private static final String VERIFY = "/api/v1/auth/verify-email";
  private static final String EMAIL = "outbox@example.com";
  private static final String USERNAME = "outboxuser";
  private static final String PASSWORD = "Str0ng-Passw0rd!";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private ProcessedEventRepository processedEventRepository;
  @Autowired private OutboxEventRecorder outboxEventRecorder;
  @Autowired private IdempotentEventProcessor idempotentEventProcessor;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private UserRepository userRepository;
  @Autowired private RefreshTokenRepository refreshTokenRepository;
  @Autowired private MfaBackupCodeRepository backupCodeRepository;
  @Autowired private EmailVerificationTokenRepository emailVerificationTokenRepository;
  @Autowired private PasswordResetTokenRepository passwordResetTokenRepository;
  @Autowired private LoginHistoryRepository loginHistoryRepository;
  @Autowired private AuditLogRepository auditLogRepository;

  @MockitoBean private EmailService emailService;

  @BeforeEach
  void setUp() {
    outboxEventRepository.deleteAll();
    processedEventRepository.deleteAll();
    backupCodeRepository.deleteAll();
    loginHistoryRepository.deleteAll();
    auditLogRepository.deleteAll();
    refreshTokenRepository.deleteAll();
    emailVerificationTokenRepository.deleteAll();
    passwordResetTokenRepository.deleteAll();
    userRepository.deleteAll();
  }

  private void signup() throws Exception {
    var body = objectMapper.writeValueAsString(Map.of(
        "firstName", "Out", "lastName", "Box", "username", USERNAME,
        "email", EMAIL, "password", PASSWORD, "organization", "DevForge"));
    mockMvc.perform(post(SIGNUP).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated());
  }

  // --- Staging -------------------------------------------------------------

  @Test
  @DisplayName("signup stages a UserRegistered event alongside the user row")
  void signupStagesEvent() throws Exception {
    signup();

    var events = outboxEventRepository.findAll();
    assertThat(events).hasSize(1);

    var event = events.get(0);
    assertThat(event.getEventType()).isEqualTo(EventTypes.USER_REGISTERED);
    assertThat(event.getTopic()).isEqualTo(KafkaTopics.IDENTITY);
    assertThat(event.isPublished()).isFalse();
    assertThat(event.getAttempts()).isZero();

    // The stored payload is a complete, parseable envelope.
    var envelope = objectMapper.readTree(event.getPayload());
    assertThat(envelope.get("eventType").asText()).isEqualTo(EventTypes.USER_REGISTERED);
    assertThat(envelope.get("version").asInt()).isEqualTo(1);
    assertThat(envelope.get("source").asText()).isEqualTo("auth-service");
    assertThat(envelope.get("eventId").asText()).isEqualTo(event.getEventId().toString());
    assertThat(envelope.get("payload").get("email").asText()).isEqualTo(EMAIL);
  }

  @Test
  @DisplayName("an event payload carries no credential material")
  void payloadCarriesNoSecrets() throws Exception {
    signup();
    var payload = outboxEventRepository.findAll().get(0).getPayload();

    // Events are broadcast to every consumer and retained by the topic, so anything here is
    // effectively published. The password and its hash must not be.
    var storedHash = userRepository.findByEmailIgnoreCase(EMAIL).orElseThrow().getPasswordHash();
    assertThat(payload).doesNotContain(PASSWORD).doesNotContain(storedHash);
  }

  @Test
  @DisplayName("email verification stages a UserVerified event")
  void verificationStagesEvent() throws Exception {
    signup();
    var token = emailVerificationTokenRepository.findAll().get(0).getToken();
    mockMvc.perform(post(VERIFY).param("token", token)).andExpect(status().isOk());

    assertThat(outboxEventRepository.findAll())
        .extracting(e -> e.getEventType())
        .containsExactly(EventTypes.USER_REGISTERED, EventTypes.USER_VERIFIED);
  }

  @Test
  @DisplayName("a rolled-back transaction leaves no event behind")
  void rollbackDiscardsEvent() {
    // This is the whole point of the outbox: the event must not survive a failed change.
    assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
      outboxEventRecorder.record(
          KafkaTopics.IDENTITY, EventTypes.USER_REGISTERED, null, UUID.randomUUID(), null,
          Map.of("userId", UUID.randomUUID().toString()));
      throw new IllegalStateException("business failure after staging the event");
    })).isInstanceOf(IllegalStateException.class);

    assertThat(outboxEventRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName("recording outside a transaction is rejected")
  void recordingRequiresATransaction() {
    // Propagation.MANDATORY: without an ambient transaction the atomicity guarantee is absent,
    // so this must fail loudly rather than silently behave like an inline publish.
    assertThatThrownBy(() -> outboxEventRecorder.record(
        KafkaTopics.IDENTITY, EventTypes.USER_REGISTERED, null, UUID.randomUUID(), null,
        Map.of("k", "v")))
        .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);

    assertThat(outboxEventRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName("each staged event gets a distinct id")
  void eventIdsAreUnique() {
    transactionTemplate.executeWithoutResult(status -> {
      for (int i = 0; i < 5; i++) {
        outboxEventRecorder.record(
            KafkaTopics.IDENTITY, EventTypes.USER_REGISTERED, null, UUID.randomUUID(), null,
            Map.of("index", i));
      }
    });

    assertThat(outboxEventRepository.findAll())
        .extracting(e -> e.getEventId())
        .doesNotHaveDuplicates()
        .hasSize(5);
  }

  @Test
  @DisplayName("events are keyed by tenant so one tenant's events stay ordered")
  void partitionKeyIsTenantScoped() {
    var tenantId = UUID.randomUUID();
    transactionTemplate.executeWithoutResult(status ->
        outboxEventRecorder.record(
            KafkaTopics.PROJECTS, EventTypes.PROJECT_CREATED, tenantId, UUID.randomUUID(), null,
            Map.of("projectId", UUID.randomUUID().toString())));

    assertThat(outboxEventRepository.findAll().get(0).getPartitionKey())
        .isEqualTo(tenantId.toString());
  }

  // --- Idempotency ---------------------------------------------------------

  @Test
  @DisplayName("a duplicate event is handled only once per consumer group")
  void duplicateIsProcessedOnce() {
    var envelope = com.devforge.ai.common.events.EventEnvelope.of(
        EventTypes.USER_REGISTERED, "auth-service", null, UUID.randomUUID(), null,
        Map.of("userId", UUID.randomUUID().toString()));
    var runs = new AtomicInteger();

    assertThat(idempotentEventProcessor.processOnce(envelope, "test-group", e -> runs.incrementAndGet()))
        .isTrue();
    // Redelivery is normal: at-least-once publishing plus Kafka rebalances guarantee it.
    assertThat(idempotentEventProcessor.processOnce(envelope, "test-group", e -> runs.incrementAndGet()))
        .isFalse();

    assertThat(runs.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("two consumer groups each process the same event once")
  void differentGroupsEachProcessOnce() {
    var envelope = com.devforge.ai.common.events.EventEnvelope.of(
        EventTypes.USER_VERIFIED, "auth-service", null, UUID.randomUUID(), null, Map.of("k", "v"));
    var runs = new AtomicInteger();

    assertThat(idempotentEventProcessor.processOnce(envelope, "group-a", e -> runs.incrementAndGet()))
        .isTrue();
    assertThat(idempotentEventProcessor.processOnce(envelope, "group-b", e -> runs.incrementAndGet()))
        .isTrue();

    assertThat(runs.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("a failing handler is not marked processed, so the event is retried")
  void failedHandlerIsRetryable() {
    var envelope = com.devforge.ai.common.events.EventEnvelope.of(
        EventTypes.USER_REGISTERED, "auth-service", null, UUID.randomUUID(), null, Map.of("k", "v"));

    assertThatThrownBy(() -> idempotentEventProcessor.processOnce(
        envelope, "test-group", e -> {
          throw new IllegalStateException("handler blew up");
        })).isInstanceOf(IllegalStateException.class);

    // Marking completion before running the handler would turn any failure into a
    // permanently skipped event, so the marker must roll back with the handler.
    assertThat(processedEventRepository.existsByEventIdAndConsumerGroup(
        envelope.eventId(), "test-group")).isFalse();

    var runs = new AtomicInteger();
    assertThat(idempotentEventProcessor.processOnce(envelope, "test-group", e -> runs.incrementAndGet()))
        .isTrue();
    assertThat(runs.get()).isEqualTo(1);
  }
}
