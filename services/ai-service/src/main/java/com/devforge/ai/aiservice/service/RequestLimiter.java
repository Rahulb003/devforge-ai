package com.devforge.ai.aiservice.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * One hourly budget per person across every AI feature, so one account cannot run up the bill.
 *
 * <p>Held in memory on each instance: with several instances the effective limit is per instance.
 * That is a cost guard, not a security boundary, and a shared store can replace it when the scale
 * needs one.
 */
@Component
public class RequestLimiter {

  private final Map<UUID, Deque<Instant>> recent = new ConcurrentHashMap<>();

  @Value("${devforge.ai.requests-per-hour:30}")
  private int requestsPerHour;

  public void take(UUID userId) {
    var now = Instant.now();
    var window = recent.computeIfAbsent(userId, id -> new ArrayDeque<>());
    synchronized (window) {
      while (!window.isEmpty() && window.peekFirst().isBefore(now.minus(Duration.ofHours(1)))) {
        window.pollFirst();
      }
      if (window.size() >= requestsPerHour) {
        throw new RateLimitedException("You have used this hour's AI requests; try again later");
      }
      window.addLast(now);
    }
  }

  public static class RateLimitedException extends RuntimeException {
    public RateLimitedException(String message) {
      super(message);
    }
  }
}
