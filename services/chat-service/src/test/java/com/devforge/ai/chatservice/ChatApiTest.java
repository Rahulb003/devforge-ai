package com.devforge.ai.chatservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devforge.ai.chatservice.repository.ChatMessageRepository;
import com.devforge.ai.common.exception.ResourceNotFoundException;
import com.devforge.ai.common.security.client.ProjectAccessClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Chat API")
class ChatApiTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private ChatMessageRepository messages;

  @MockitoBean private ProjectAccessClient projectAccessClient;

  private final UUID organizationId = UUID.randomUUID();
  private final UUID projectId = UUID.randomUUID();
  private final UUID alice = UUID.randomUUID();
  private final UUID bob = UUID.randomUUID();

  private String base() {
    return "/api/v1/organizations/" + organizationId + "/projects/" + projectId + "/chat/messages";
  }

  private String bearer(UUID user) {
    return "Bearer " + TestTokens.accessToken(user);
  }

  @BeforeEach
  void setUp() {
    messages.deleteAll();
  }

  private UUID postAs(UUID user, String body) throws Exception {
    var json = mockMvc.perform(post(base())
            .header(HttpHeaders.AUTHORIZATION, bearer(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"body\":" + objectMapper.writeValueAsString(body) + "}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return UUID.fromString(objectMapper.readTree(json).path("data").path("id").asText());
  }

  @Test
  @DisplayName("posts and lists messages newest first, with the author from the token")
  void postAndList() throws Exception {
    postAs(alice, "first");
    Thread.sleep(5);
    postAs(bob, "second");

    mockMvc.perform(get(base()).header(HttpHeaders.AUTHORIZATION, bearer(alice)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].body").value("second"))
        .andExpect(jsonPath("$.data[0].authorId").value(bob.toString()))
        .andExpect(jsonPath("$.data[1].body").value("first"));
  }

  @Test
  @DisplayName("polling with 'after' returns only newer messages, oldest first")
  void pollsForNewMessages() throws Exception {
    postAs(alice, "old");
    var cutoff = messages.findAll().get(0).getCreatedAt();
    Thread.sleep(5);
    postAs(alice, "new one");
    Thread.sleep(5);
    postAs(alice, "new two");

    mockMvc.perform(get(base()).param("after", cutoff.toString())
            .header(HttpHeaders.AUTHORIZATION, bearer(alice)))
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].body").value("new one"))
        .andExpect(jsonPath("$.data[1].body").value("new two"));
  }

  @Test
  @DisplayName("rejects an empty or oversized message")
  void validatesBody() throws Exception {
    for (var body : new String[] {"{\"body\":\"\"}", "{\"body\":\"   \"}", "{}",
        "{\"body\":\"" + "x".repeat(4001) + "\"}"}) {
      mockMvc.perform(post(base())
              .header(HttpHeaders.AUTHORIZATION, bearer(alice))
              .contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isBadRequest());
    }
    assertThat(messages.count()).isZero();
  }

  @Test
  @DisplayName("the author can edit their message; someone else cannot")
  void onlyAuthorEdits() throws Exception {
    var id = postAs(alice, "draft");

    mockMvc.perform(patch(base() + "/" + id)
            .header(HttpHeaders.AUTHORIZATION, bearer(bob))
            .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"hijacked\"}"))
        .andExpect(status().isForbidden());

    mockMvc.perform(patch(base() + "/" + id)
            .header(HttpHeaders.AUTHORIZATION, bearer(alice))
            .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"final\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.body").value("final"))
        .andExpect(jsonPath("$.data.edited").value(true));
  }

  @Test
  @DisplayName("deleting clears the text from the database, not just the screen")
  void deleteErasesBody() throws Exception {
    var id = postAs(alice, "something I regret");

    mockMvc.perform(delete(base() + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(bob)))
        .andExpect(status().isForbidden());
    mockMvc.perform(delete(base() + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(alice)))
        .andExpect(status().isOk());

    var row = messages.findById(id).orElseThrow();
    assertThat(row.isDeleted()).isTrue();
    assertThat(row.getBody()).doesNotContain("regret");

    mockMvc.perform(get(base()).header(HttpHeaders.AUTHORIZATION, bearer(bob)))
        .andExpect(jsonPath("$.data[0].deleted").value(true))
        .andExpect(jsonPath("$.data[0].body").doesNotExist());
  }

  @Test
  @DisplayName("a message from another project's channel is 404")
  void otherChannelIsNotFound() throws Exception {
    var id = postAs(alice, "private");
    var other = "/api/v1/organizations/" + organizationId + "/projects/" + UUID.randomUUID()
        + "/chat/messages/" + id;

    mockMvc.perform(delete(other).header(HttpHeaders.AUTHORIZATION, bearer(alice)))
        .andExpect(status().isNotFound());
    assertThat(messages.findById(id).orElseThrow().isDeleted()).isFalse();
  }

  @Test
  @DisplayName("a caller without project access can neither read nor post")
  void deniedProjectAccess() throws Exception {
    doThrow(new ResourceNotFoundException("Project not found"))
        .when(projectAccessClient).requireProjectAccess(eq(organizationId), eq(projectId), any());

    mockMvc.perform(get(base()).header(HttpHeaders.AUTHORIZATION, bearer(alice)))
        .andExpect(status().isNotFound());
    mockMvc.perform(post(base()).header(HttpHeaders.AUTHORIZATION, bearer(alice))
            .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"hi\"}"))
        .andExpect(status().isNotFound());
    assertThat(messages.count()).isZero();
  }

  @Test
  @DisplayName("tokens are verified and an unreachable authority fails closed")
  void authAndFailClosed() throws Exception {
    mockMvc.perform(get(base())).andExpect(status().isUnauthorized());
    mockMvc.perform(get(base()).header(HttpHeaders.AUTHORIZATION,
            "Bearer " + TestTokens.refreshToken(alice)))
        .andExpect(status().isUnauthorized());

    doThrow(new ProjectAccessClient.ProjectServiceUnavailableException("down", null))
        .when(projectAccessClient).requireProjectAccess(any(), any(), any());
    mockMvc.perform(get(base()).header(HttpHeaders.AUTHORIZATION, bearer(alice)))
        .andExpect(status().isServiceUnavailable());
  }
}
