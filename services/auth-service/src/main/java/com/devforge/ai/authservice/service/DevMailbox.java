package com.devforge.ai.authservice.service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * In-memory capture of messages the development mail provider did not send.
 *
 * <p>Exists because a verification link that only reaches a log file is effectively lost: with the
 * services started in their own console windows, there is nowhere obvious to read it, and the
 * signup flow dead-ends. This is the same idea as MailHog or Mailpit, without another process to
 * install.
 *
 * <p><b>These messages contain single-use credentials.</b> Verification and reset tokens are
 * exactly what an attacker needs to take over an account, so this bean only exists when the
 * development mail provider is active, and the endpoint that reads it is separately gated.
 *
 * <p>Deliberately not persisted. Messages are for the current session only, and a restart should
 * not leave live tokens sitting in a file.
 */
@Component
@ConditionalOnProperty(name = "devforge.mail.provider", havingValue = "log")
public class DevMailbox {

  /** Bounded so a long-running dev session cannot grow this without limit. */
  private static final int MAX_MESSAGES = 50;

  public record CapturedMessage(
      Instant sentAt, String to, String subject, String body, String actionUrl) {}

  private final Deque<CapturedMessage> messages = new ArrayDeque<>();

  public synchronized void capture(String to, String subject, String body, String actionUrl) {
    messages.addFirst(new CapturedMessage(Instant.now(), to, subject, body, actionUrl));
    while (messages.size() > MAX_MESSAGES) {
      messages.removeLast();
    }
  }

  /** Newest first. */
  public synchronized List<CapturedMessage> all() {
    return List.copyOf(messages);
  }

  public synchronized void clear() {
    messages.clear();
  }
}
