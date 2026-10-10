package com.devforge.ai.gitservice.webhook;

import com.devforge.ai.gitservice.repository.WebhookRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers webhooks once a branch change has committed.
 *
 * <p>After commit, so a receiver is never told about a change that was rolled back; off the request
 * thread, so a slow receiver cannot slow a push. Three attempts with backoff, then the failure is
 * recorded on the webhook for its admin to see. Delivery is best-effort: a webhook queued when the
 * process stops is lost - a durable queue is the next step if receivers need every event.
 *
 * <p>Each request carries {@code X-DevForge-Signature: sha256=<hmac>} over the exact body, keyed by
 * the webhook's secret, so a receiver can tell it came from here. Redirects are not followed - a
 * redirect would take the request past the address check.
 */
@Slf4j
@Component
public class WebhookDispatcher {

  private static final Duration[] BACKOFF = {Duration.ZERO, Duration.ofSeconds(2), Duration.ofSeconds(10)};

  private final WebhookRepository webhooks;
  private final WebhookUrlGuard guard;
  private final TransactionTemplate transactions;
  private final ObjectMapper objectMapper;
  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .followRedirects(HttpClient.Redirect.NEVER)
      .build();
  // Bounded: a flood of pushes queues at most this many deliveries rather than growing without end.
  private final ExecutorService executor = new ThreadPoolExecutor(
      2, 4, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(1000), new ThreadPoolExecutor.DiscardOldestPolicy());

  public WebhookDispatcher(WebhookRepository webhooks, WebhookUrlGuard guard,
      TransactionTemplate transactions, ObjectMapper objectMapper) {
    this.webhooks = webhooks;
    this.guard = guard;
    this.transactions = transactions;
    this.objectMapper = objectMapper;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onBranchChanged(BranchChanged change) {
    var targets = webhooks.findByRepositoryIdOrderByCreatedAtAsc(change.repositoryId());
    if (targets.isEmpty()) {
      return;
    }
    var payload = new LinkedHashMap<String, Object>();
    payload.put("event", "push");
    payload.put("deliveryId", UUID.randomUUID().toString());
    payload.put("repositoryId", change.repositoryId().toString());
    payload.put("projectId", change.projectId().toString());
    payload.put("branch", change.branch());
    payload.put("commitId", change.commitId());
    payload.put("actor", change.actor());
    payload.put("via", change.via());
    payload.put("occurredAt", Instant.now().toString());
    String body;
    try {
      body = objectMapper.writeValueAsString(payload);
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      throw new IllegalStateException("Could not serialise a webhook payload", ex);
    }
    for (var target : targets) {
      var id = target.getId();
      var url = target.getUrl();
      var signature = sign(target.getSecret(), body);
      executor.submit(() -> deliver(id, url, body, signature));
    }
  }

  void deliver(UUID webhookId, String url, String body, String signature) {
    Integer status = null;
    String error = null;
    for (var wait : BACKOFF) {
      try {
        if (!wait.isZero()) {
          Thread.sleep(wait.toMillis());
        }
        // Again at delivery: the name may resolve somewhere else now than when the hook was saved.
        var uri = guard.check(url);
        var response = http.send(HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("User-Agent", "DevForge-Webhooks")
                .header("X-DevForge-Event", "push")
                .header("X-DevForge-Signature", "sha256=" + signature)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.discarding());
        status = response.statusCode();
        error = status >= 200 && status < 300 ? null : "Answered " + status;
        if (error == null) {
          break;
        }
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        error = "Interrupted";
        break;
      } catch (IllegalArgumentException ex) {
        // Refused by the guard: retrying will not change that.
        error = ex.getMessage();
        break;
      } catch (Exception ex) {
        error = ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
      }
    }
    var finalStatus = status;
    var finalError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
    if (finalError != null) {
      log.warn("Webhook {} delivery failed: {}", webhookId, finalError);
    }
    transactions.executeWithoutResult(tx -> webhooks.findById(webhookId).ifPresent(w -> {
      w.setLastStatus(finalStatus);
      w.setLastError(finalError);
      w.setLastDeliveredAt(Instant.now());
    }));
  }

  static String sign(String secret, String body) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException ex) {
      throw new IllegalStateException("HmacSHA256 is required of every JVM", ex);
    }
  }

  @PreDestroy
  void stop() {
    executor.shutdown();
  }
}
