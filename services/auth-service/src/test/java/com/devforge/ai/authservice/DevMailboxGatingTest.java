package com.devforge.ai.authservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devforge.ai.authservice.service.DevMailbox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The development mailbox serves single-use account credentials, so its gating is a security
 * control and is tested as one rather than trusted to an annotation.
 */
class DevMailboxGatingTest {

  private static final String ENDPOINT = "/api/v1/dev/mailbox";

  @Nested
  @SpringBootTest
  @AutoConfigureMockMvc
  @DisplayName("when the dev mailbox is disabled (the default)")
  class Disabled {

    @Autowired private MockMvc mockMvc;
    @Autowired private ApplicationContext context;

    @Test
    @DisplayName("the endpoint does not exist")
    void endpointIsAbsent() throws Exception {
      // 404 because no handler is registered at all, not 401 or 403: the controller
      // is never created, so there is nothing to reach even with a valid session.
      mockMvc.perform(get(ENDPOINT)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the controller bean is not created")
    void controllerBeanIsAbsent() {
      assertThat(context.getBeanNamesForType(
          com.devforge.ai.authservice.controller.DevMailboxController.class)).isEmpty();
    }
  }

  @Nested
  @SpringBootTest(properties = {
      "devforge.mail.provider=log",
      "devforge.mail.dev-mailbox-enabled=true",
  })
  @AutoConfigureMockMvc
  @DisplayName("when the dev mailbox is explicitly enabled")
  class Enabled {

    @Autowired private MockMvc mockMvc;
    @Autowired private DevMailbox mailbox;

    @Test
    @DisplayName("the endpoint is reachable without authentication")
    void endpointIsReachable() throws Exception {
      // Unauthenticated by necessity: it exists to finish a signup before there is
      // an account to sign in with. That is why the gating above carries the weight.
      mockMvc.perform(get(ENDPOINT))
          .andExpect(status().isOk());
    }

    @Test
    @DisplayName("captured messages are returned")
    void capturedMessagesAreReturned() throws Exception {
      mailbox.clear();
      mailbox.capture("someone@example.com", "Verify your DevForge AI email", "body",
          "http://localhost:4173/verify-email?token=abc");

      mockMvc.perform(get(ENDPOINT))
          .andExpect(status().isOk())
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
              .jsonPath("$.data[0].to").value("someone@example.com"))
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
              .jsonPath("$.data[0].actionUrl")
              .value("http://localhost:4173/verify-email?token=abc"));
    }

    @Test
    @DisplayName("the mailbox is bounded so it cannot grow without limit")
    void mailboxIsBounded() {
      mailbox.clear();
      for (int i = 0; i < 60; i++) {
        mailbox.capture("user" + i + "@example.com", "Subject " + i, "body", "http://localhost/x");
      }

      assertThat(mailbox.all()).hasSize(50);
      // Newest first, so the most recent capture must survive the eviction.
      assertThat(mailbox.all().get(0).to()).isEqualTo("user59@example.com");
    }
  }
}
