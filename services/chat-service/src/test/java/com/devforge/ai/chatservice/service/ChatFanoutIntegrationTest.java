package com.devforge.ai.chatservice.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.devforge.ai.chatservice.dto.ChatDtos.MessageResponse;
import com.devforge.ai.common.security.client.ProjectAccessClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Chat delivery across instances, through a real broker.
 *
 * <p>The other instance is played by publishing its relay directly: a second application context
 * would prove the same thing at several times the cost. What matters is that this instance's
 * listener, in its own group, picks the relay up and pushes it to local streams - and that it does
 * not push its own relays a second time.
 */
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = ChatFanout.TOPIC)
@TestPropertySource(properties = {
    "devforge.kafka.enabled=true",
    "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
})
@DisplayName("Chat fan-out against a real Kafka broker")
class ChatFanoutIntegrationTest {

  @Autowired private ChatFanout fanout;
  @Autowired private KafkaTemplate<String, String> kafkaTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private KafkaListenerEndpointRegistry listeners;
  @MockitoSpyBean private ChatStreamRegistry streams;
  @MockitoBean private ProjectAccessClient projectAccessClient;

  private final UUID projectId = UUID.randomUUID();

  @BeforeEach
  void waitForTheListener() {
    // The listener starts at the latest offset, so anything sent before it holds its partition
    // would be skipped - which would look exactly like a delivery bug.
    listeners.getListenerContainers()
        .forEach(container -> ContainerTestUtils.waitForAssignment(container, 1));
  }

  private static MessageResponse message(String body) {
    return new MessageResponse(UUID.randomUUID(), UUID.randomUUID(), "ada", body, false, false,
        Instant.now());
  }

  private void relayFromAnotherInstance(MessageResponse message) throws Exception {
    var relay = new ChatFanout.Relay(UUID.randomUUID().toString(), projectId, message);
    kafkaTemplate.send(ChatFanout.TOPIC, projectId.toString(), objectMapper.writeValueAsString(relay))
        .get();
  }

  @Test
  @DisplayName("a message posted on another instance reaches streams on this one")
  void deliversRelaysFromOtherInstances() throws Exception {
    var message = message("from elsewhere");
    relayFromAnotherInstance(message);

    verify(streams, timeout(10_000)).broadcast(eq(projectId), eq(message));
  }

  @Test
  @DisplayName("a message posted here is pushed once, not again when its relay comes back")
  void doesNotDeliverItsOwnRelayTwice() throws Exception {
    var own = message("from here");
    fanout.deliver(projectId, own);
    // Same key, same partition: once this later relay has been delivered, our own has been read.
    var marker = message("marker");
    relayFromAnotherInstance(marker);

    verify(streams, timeout(10_000)).broadcast(eq(projectId), eq(marker));
    verify(streams, times(1)).broadcast(eq(projectId), eq(own));
  }

  @Test
  @DisplayName("a presence change on another instance reaches streams on this one")
  void relaysPresenceChanges() throws Exception {
    var relay = new ChatFanout.Relay(UUID.randomUUID().toString(), projectId, null, true);
    kafkaTemplate.send(ChatFanout.TOPIC, projectId.toString(), objectMapper.writeValueAsString(relay))
        .get();

    verify(streams, timeout(10_000)).broadcastPresenceChanged(projectId);
  }

  @Test
  @DisplayName("an unreadable relay is dropped without stopping the listener")
  void survivesGarbage() throws Exception {
    kafkaTemplate.send(ChatFanout.TOPIC, projectId.toString(), "not json").get();
    var message = message("after garbage");
    relayFromAnotherInstance(message);

    verify(streams, timeout(10_000)).broadcast(eq(projectId), eq(message));
    verify(streams, times(0)).broadcast(any(), argThat(m -> m == null));
  }
}
