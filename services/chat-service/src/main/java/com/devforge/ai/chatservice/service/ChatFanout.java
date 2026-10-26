package com.devforge.ai.chatservice.service;

import com.devforge.ai.chatservice.dto.ChatDtos.MessageResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Delivers a chat message to the open streams on every chat-service instance, not just this one.
 *
 * <p>Local subscribers get the message directly, so delivery on a single instance never depends on
 * the broker. It is then relayed over {@link #TOPIC}, which every instance reads in a consumer group
 * of its own - Kafka delivers to each group independently, so all of them see every message - and
 * an instance skips what it relayed itself, having delivered it already.
 *
 * <p>Best effort, deliberately: this is a live push of a message that is already committed. A relay
 * that fails costs a subscriber on another instance the push, not the message, which the next
 * history load shows. So nothing here is retried, dead-lettered or deduplicated.
 */
@Slf4j
@Component
public class ChatFanout {

  public static final String TOPIC = "devforge.chat.v1";

  /**
   * Group ids share this prefix so one prefixed ACL covers every instance. Each instance's id is
   * fresh, so a restarted instance does not resume an old offset and replay stale pushes.
   */
  public static final String GROUP_PREFIX = "chat-service.fanout.";

  /** Relayed message. Package-visible for the tests. */
  record Relay(String origin, UUID projectId, MessageResponse message) {}

  private final String instanceId = UUID.randomUUID().toString();
  private final ChatStreamRegistry streams;
  private final ObjectMapper objectMapper;
  private final ObjectProvider<KafkaTemplate<String, String>> kafka;
  private final boolean relayEnabled;

  public ChatFanout(ChatStreamRegistry streams, ObjectMapper objectMapper,
      ObjectProvider<KafkaTemplate<String, String>> kafka,
      @Value("${devforge.kafka.enabled:true}") boolean relayEnabled) {
    this.streams = streams;
    this.objectMapper = objectMapper;
    this.kafka = kafka;
    this.relayEnabled = relayEnabled;
  }

  public String groupId() {
    return GROUP_PREFIX + instanceId;
  }

  public void deliver(UUID projectId, MessageResponse message) {
    streams.broadcast(projectId, message);
    if (!relayEnabled) {
      return;
    }
    try {
      var payload = objectMapper.writeValueAsString(new Relay(instanceId, projectId, message));
      // Keyed by project so one channel's messages stay on one partition, in order.
      kafka.getObject().send(TOPIC, projectId.toString(), payload)
          .whenComplete((result, ex) -> {
            if (ex != null) {
              log.warn("Chat relay failed for project {}: {}", projectId, ex.getMessage());
            }
          });
    } catch (Exception ex) {
      log.warn("Chat relay failed for project {}: {}", projectId, ex.getMessage());
    }
  }

  /**
   * Starts at the latest offset: a push is only worth anything to someone connected now, and an
   * instance that started from the beginning would just read the whole topic to throw it away.
   */
  @KafkaListener(topics = TOPIC, groupId = "#{__listener.groupId()}",
      autoStartup = "${devforge.kafka.enabled:true}",
      properties = "auto.offset.reset=latest")
  public void onRelay(String payload) {
    try {
      var relay = objectMapper.readValue(payload, Relay.class);
      if (!instanceId.equals(relay.origin())) {
        streams.broadcast(relay.projectId(), relay.message());
      }
    } catch (Exception ex) {
      // Swallowed rather than handed to the container's error handler: that would retry and then
      // dead-letter a push nobody needs later, to a topic this service has no grant to write.
      log.warn("Dropped an unreadable chat relay: {}", ex.getMessage());
    }
  }
}
