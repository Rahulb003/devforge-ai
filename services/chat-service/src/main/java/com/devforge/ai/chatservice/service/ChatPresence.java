package com.devforge.ai.chatservice.service;

import com.devforge.ai.common.security.AuthenticatedUser;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Who has a project's channel open.
 *
 * <p>Kept in the database rather than in memory, because the streams of one channel can be spread
 * over several instances and every one of them must give the same answer. Each open stream is a
 * row this instance keeps alive with a heartbeat; a row whose heartbeat stops - its instance died -
 * expires and is swept by whichever instance notices first.
 *
 * <p>A change is announced as a bare "presence changed" event, relayed to every instance like a
 * message, and clients re-read the list. Pushing the list itself would let two changes computed at
 * almost the same moment arrive in the wrong order and leave a client showing the older one.
 */
@Slf4j
@Component
@EnableScheduling
public class ChatPresence {

  static final Duration HEARTBEAT = Duration.ofSeconds(30);
  /** Three missed heartbeats: long enough to ride out a slow one, short enough to be believable. */
  static final Duration EXPIRY = HEARTBEAT.multipliedBy(3);

  public record Present(UUID userId, String username) {}

  private record Connection(UUID projectId, SseEmitter emitter) {}

  private final NamedParameterJdbcTemplate jdbc;
  private final ChatFanout fanout;
  private final Map<UUID, Connection> local = new ConcurrentHashMap<>();

  public ChatPresence(NamedParameterJdbcTemplate jdbc, ChatFanout fanout) {
    this.jdbc = jdbc;
    this.fanout = fanout;
  }

  public void join(UUID projectId, AuthenticatedUser user, SseEmitter emitter) {
    var connectionId = UUID.randomUUID();
    jdbc.update("INSERT INTO chat_presence (connection_id, project_id, user_id, user_name, instance_id,"
            + " last_seen) VALUES (:c, :p, :u, :n, :i, :t)",
        new MapSqlParameterSource()
            .addValue("c", connectionId).addValue("p", projectId).addValue("u", user.id())
            .addValue("n", user.username()).addValue("i", fanout.instanceId())
            .addValue("t", Timestamp.from(Instant.now())));
    local.put(connectionId, new Connection(projectId, emitter));
    Runnable leave = () -> leave(connectionId);
    emitter.onCompletion(leave);
    emitter.onTimeout(leave);
    emitter.onError(e -> leave.run());
    fanout.presenceChanged(projectId);
  }

  void leave(UUID connectionId) {
    var connection = local.remove(connectionId);
    if (connection == null) {
      return;
    }
    jdbc.update("DELETE FROM chat_presence WHERE connection_id = :c",
        new MapSqlParameterSource("c", connectionId));
    fanout.presenceChanged(connection.projectId());
  }

  /** One row per person, however many tabs they have open. */
  public List<Present> who(UUID projectId) {
    return jdbc.query("SELECT user_id, MIN(user_name) AS user_name FROM chat_presence"
            + " WHERE project_id = :p AND last_seen > :cutoff GROUP BY user_id ORDER BY MIN(user_name)",
        new MapSqlParameterSource("p", projectId)
            .addValue("cutoff", Timestamp.from(Instant.now().minus(EXPIRY))),
        (rs, i) -> new Present(rs.getObject("user_id", UUID.class), rs.getString("user_name")));
  }

  /**
   * Pings every local stream - which is also how a client that vanished without closing is found,
   * since a server only learns a connection is dead when a write to it fails - then renews this
   * instance's rows and sweeps everyone's expired ones.
   */
  @Scheduled(fixedDelayString = "${devforge.chat.presence-heartbeat-ms:30000}")
  public void heartbeat() {
    for (var entry : local.entrySet()) {
      try {
        entry.getValue().emitter().send(SseEmitter.event().comment("ping"));
      } catch (IOException | IllegalStateException ex) {
        entry.getValue().emitter().completeWithError(ex);
        leave(entry.getKey());
      }
    }
    if (!local.isEmpty()) {
      jdbc.update("UPDATE chat_presence SET last_seen = :t WHERE connection_id IN (:ids)",
          new MapSqlParameterSource("t", Timestamp.from(Instant.now()))
              .addValue("ids", List.copyOf(local.keySet())));
    }
    sweep();
  }

  void sweep() {
    var cutoff = Timestamp.from(Instant.now().minus(EXPIRY));
    var params = new MapSqlParameterSource("cutoff", cutoff);
    var affected = new HashSet<>(jdbc.queryForList(
        "SELECT DISTINCT project_id FROM chat_presence WHERE last_seen <= :cutoff", params, UUID.class));
    if (affected.isEmpty()) {
      return;
    }
    jdbc.update("DELETE FROM chat_presence WHERE last_seen <= :cutoff", params);
    affected.forEach(fanout::presenceChanged);
  }

  /** Open streams on this instance. For tests. */
  int localConnections() {
    return local.size();
  }
}
