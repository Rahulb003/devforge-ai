package com.devforge.ai.chatservice.service;

import com.devforge.ai.chatservice.dto.ChatDtos.MessageResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Open Server-Sent Event streams, per project channel.
 *
 * <p>In-memory, so it fans out only to clients connected to <em>this</em> instance. With more than
 * one chat-service instance a message posted on one would not reach subscribers on another; that
 * needs a shared channel (Kafka or Redis pub/sub) and is recorded as a limitation, not hidden.
 */
@Slf4j
@Component
public class ChatStreamRegistry {

  private final Map<UUID, Set<SseEmitter>> streams = new ConcurrentHashMap<>();

  /**
   * How long one stream may stay open.
   *
   * <p>Matched to the access token's lifetime. Authorization is checked once, when the stream
   * opens, so an unbounded stream would keep delivering messages after the token that justified it
   * expired — or after the user was removed from the project. Closing it forces the client to
   * reconnect with a fresh token, which re-runs the check.
   */
  @Value("${devforge.chat.stream-timeout-ms:900000}")
  private long timeoutMs;

  public SseEmitter subscribe(UUID projectId) {
    var emitter = new SseEmitter(timeoutMs);
    var set = streams.computeIfAbsent(projectId, k -> ConcurrentHashMap.newKeySet());
    set.add(emitter);
    Runnable drop = () -> set.remove(emitter);
    emitter.onCompletion(drop);
    emitter.onTimeout(drop);
    emitter.onError(e -> drop.run());

    // Sent at once, to flush the response headers. Spring does not commit an SSE response until the
    // first event is written, so on a quiet channel the client saw no headers at all and could not
    // tell an open stream from a hung one. A curl probe hid this because it posted a message, which
    // forced the flush; a browser waiting for the stream before posting never got there. A comment
    // line carries no "data:", so clients do not mistake it for a message.
    try {
      emitter.send(SseEmitter.event().comment("connected"));
    } catch (IOException ex) {
      set.remove(emitter);
    }
    return emitter;
  }

  public void broadcast(UUID projectId, MessageResponse message) {
    var set = streams.get(projectId);
    if (set == null) {
      return;
    }
    for (var emitter : set) {
      try {
        emitter.send(SseEmitter.event().name("message").data(message));
      } catch (IOException | IllegalStateException ex) {
        // A client that went away. Dropped quietly: one dead connection must not stop delivery to
        // everyone else in the channel.
        set.remove(emitter);
      }
    }
  }

  /** Open streams for a project. For tests and diagnostics. */
  int subscribers(UUID projectId) {
    var set = streams.get(projectId);
    return set == null ? 0 : set.size();
  }
}
