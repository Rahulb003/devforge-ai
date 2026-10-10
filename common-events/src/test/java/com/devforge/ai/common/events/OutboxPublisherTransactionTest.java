package com.devforge.ai.common.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.devforge.ai.common.events.outbox.OutboxEvent;
import com.devforge.ai.common.events.outbox.OutboxEventRepository;
import com.devforge.ai.common.events.outbox.OutboxPublisher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The scheduled drain holds one transaction from the claim to the marking.
 *
 * <p>This is the property FOR UPDATE SKIP LOCKED depends on, and it was broken: the scheduler's
 * entry point called {@code drainOnce} on {@code this}, bypassing the proxy that applied
 * {@code @Transactional}, so the claim's locks ended with the query. The integration tests never
 * saw it because they called {@code drainOnce} through the proxy. This calls what the scheduler
 * calls, and records the transaction every step ran in.
 */
@DisplayName("Outbox drain transaction boundary")
class OutboxPublisherTransactionTest {

  /** Begins and commits nothing real, but marks a transaction active as a real manager does. */
  static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
    int begun;

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      begun++;
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) { }

    @Override
    protected void doRollback(DefaultTransactionStatus status) { }
  }

  @Test
  @DisplayName("the claim, the sends and the marking all run in the transaction the scheduler's call opened")
  @SuppressWarnings("unchecked")
  void scheduledDrainHoldsOneTransaction() {
    var steps = new ArrayList<String>();
    var repository = mock(OutboxEventRepository.class);
    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    var transactions = new RecordingTransactionManager();

    var event = OutboxEvent.builder()
        .id(UUID.randomUUID()).eventId(UUID.randomUUID()).eventType("TaskCreated")
        .topic("devforge.tasks.v1").partitionKey("k").payload("{}").createdAt(Instant.now())
        .build();
    when(repository.claimUnpublished(anyInt())).thenAnswer(invocation -> {
      steps.add("claim:" + TransactionSynchronizationManager.isActualTransactionActive());
      return List.of(event);
    });
    when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
      steps.add("send:" + TransactionSynchronizationManager.isActualTransactionActive());
      return CompletableFuture.completedFuture(null);
    });
    when(repository.save(any())).thenAnswer(invocation -> {
      steps.add("mark:" + TransactionSynchronizationManager.isActualTransactionActive());
      return invocation.getArgument(0);
    });

    var publisher = new OutboxPublisher(repository, kafka, transactions);
    ReflectionTestUtils.setField(publisher, "batchSize", 100);
    ReflectionTestUtils.setField(publisher, "sendTimeoutSeconds", 5);
    ReflectionTestUtils.setField(publisher, "alertAfterAttempts", 10);
    ReflectionTestUtils.setField(publisher, "useSkipLocked", true);

    // What the scheduler invokes - not drainOnce through a proxy.
    publisher.publishPending();

    assertThat(steps).containsExactly("claim:true", "send:true", "mark:true");
    // One transaction for the batch, so the locks taken by the claim last until it is marked.
    assertThat(transactions.begun).isEqualTo(1);
    assertThat(event.getPublishedAt()).isNotNull();
  }
}
