package com.devforge.ai.chatservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devforge.ai.chatservice.service.ChatPresence;
import com.devforge.ai.common.exception.ResourceNotFoundException;
import com.devforge.ai.common.security.client.ProjectAccessClient;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Who is in a channel: joined by opening the stream, shared through the database, self-expiring. */
@SpringBootTest
@AutoConfigureMockMvc
// The heartbeat is driven by hand here, so its schedule must not race the assertions.
@TestPropertySource(properties = "devforge.chat.presence-heartbeat-ms=3600000")
@DisplayName("Chat presence")
class ChatPresenceTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ChatPresence presence;
  @MockitoBean private ProjectAccessClient projectAccessClient;

  private final UUID organizationId = UUID.randomUUID();
  private final UUID projectId = UUID.randomUUID();
  private final UUID alice = UUID.randomUUID();
  private final UUID bob = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM chat_presence");
  }

  private String base() {
    return "/api/v1/organizations/" + organizationId + "/projects/" + projectId + "/chat/messages";
  }

  private MvcResult open(UUID user) throws Exception {
    return mockMvc.perform(get(base() + "/stream")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.accessToken(user)))
        .andExpect(request().asyncStarted())
        .andReturn();
  }

  private String presenceAs(UUID user) throws Exception {
    return mockMvc.perform(get(base() + "/presence")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.accessToken(user)))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
  }

  @Test
  @DisplayName("opening the stream puts you in the channel, once however many tabs you have")
  void joinsOnOpen() throws Exception {
    open(alice);
    open(alice);
    var bobStream = open(bob);

    mockMvc.perform(get(base() + "/presence")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.accessToken(bob)))
        .andExpect(jsonPath("$.data.length()").value(2));
    assertThat(presenceAs(bob)).contains(alice.toString(), bob.toString());

    // Others already connected are told to re-read the list, not handed one.
    assertThat(bobStream.getResponse().getContentAsString()).contains("event:presence");
  }

  @Test
  @DisplayName("closing the stream takes you out")
  void leavesOnClose() throws Exception {
    var stream = open(alice);
    assertThat(presenceAs(alice)).contains(alice.toString());

    stream.getRequest().getAsyncContext().complete();

    assertThat(presenceAs(alice)).doesNotContain(alice.toString());
  }

  @Test
  @DisplayName("a row whose instance died stops counting, and the heartbeat sweeps it")
  void deadInstanceExpires() throws Exception {
    var ghost = UUID.randomUUID();
    jdbc.update("INSERT INTO chat_presence VALUES (?, ?, ?, 'ghost', 'dead-instance', ?)",
        UUID.randomUUID(), projectId, ghost, Timestamp.from(Instant.now().minus(Duration.ofMinutes(5))));

    assertThat(presenceAs(alice)).doesNotContain(ghost.toString());

    presence.heartbeat();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_presence", Integer.class)).isZero();
  }

  @Test
  @DisplayName("the heartbeat keeps a live connection's row fresh")
  void heartbeatRenews() throws Exception {
    open(alice);
    jdbc.update("UPDATE chat_presence SET last_seen = ?", Timestamp.from(Instant.now().minusSeconds(80)));

    presence.heartbeat();

    var seen = jdbc.queryForObject("SELECT last_seen FROM chat_presence", Timestamp.class);
    assertThat(seen.toInstant()).isAfter(Instant.now().minusSeconds(10));
    assertThat(presenceAs(alice)).contains(alice.toString());
  }

  @Test
  @DisplayName("someone outside the project cannot see who is in it")
  void needsAccess() throws Exception {
    doThrow(new ResourceNotFoundException("Project not found"))
        .when(projectAccessClient).requireProjectAccess(eq(organizationId), eq(projectId), any(), any());

    mockMvc.perform(get(base() + "/presence")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.accessToken(alice)))
        .andExpect(status().isNotFound());
  }
}
