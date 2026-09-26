package com.devforge.ai.common.events.config;

import com.devforge.ai.common.events.KafkaTopics;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Producer and consumer defaults for every DevForge service.
 *
 * <p>Centralised because the reliability properties below are easy to get wrong per service and
 * expensive when they are: the difference between {@code acks=1} and {@code acks=all} is whether a
 * broker failover loses acknowledged events.
 */
@Slf4j
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "devforge.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaConfig {

  /**
   * Producer tuned for durability over throughput.
   *
   * <ul>
   *   <li>{@code acks=all} — the write must reach every in-sync replica. With {@code acks=1} a
   *       leader crash after acknowledging loses the event, and the outbox has already marked it
   *       published.
   *   <li>{@code enable.idempotence=true} — a producer-side retry cannot create a duplicate, which
   *       would otherwise be the common source of them.
   *   <li>{@code max.in.flight=5} — the highest value that still preserves ordering when
   *       idempotence is on.
   * </ul>
   */
  @Bean
  public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
    var configs = new java.util.HashMap<>(producerFactory.getConfigurationProperties());
    configs.put(ProducerConfig.ACKS_CONFIG, "all");
    configs.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    configs.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
    configs.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
    configs.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);
    configs.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");

    return new KafkaTemplate<>(
        new org.springframework.kafka.core.DefaultKafkaProducerFactory<>(configs));
  }

  /**
   * Consumer error handling: bounded retry, then the dead-letter topic.
   *
   * <p>The important property is that a message which can never succeed — a malformed payload, a
   * referenced row that no longer exists — is moved aside instead of being retried forever. An
   * unbounded retry on a poison message blocks its partition, so one bad event stalls every event
   * behind it.
   *
   * <p>Backoff is exponential so a transient dependency outage is absorbed without hammering it.
   */
  @Bean
  public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
    var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
        (record, exception) -> {
          var deadLetterTopic = KafkaTopics.deadLetterTopicFor(record.topic());
          log.error("Routing unprocessable record from {} to {} after retries exhausted: {}",
              record.topic(), deadLetterTopic, exception.getMessage());
          // Same partition on the DLT, so a tenant's failures stay ordered relative to each other.
          return new TopicPartition(deadLetterTopic, record.partition());
        });

    var backOff = new ExponentialBackOff();
    backOff.setInitialInterval(Duration.ofSeconds(1).toMillis());
    backOff.setMultiplier(2.0);
    backOff.setMaxInterval(Duration.ofSeconds(30).toMillis());
    // Bounded total: past this the message is dead-lettered rather than retried indefinitely.
    backOff.setMaxElapsedTime(Duration.ofMinutes(2).toMillis());

    var errorHandler = new DefaultErrorHandler(recoverer, backOff);

    // Deserialisation and payload-shape failures can never succeed on retry, so they go
    // straight to the DLT instead of burning the backoff budget first.
    errorHandler.addNotRetryableExceptions(
        org.springframework.kafka.support.serializer.DeserializationException.class,
        org.springframework.messaging.converter.MessageConversionException.class,
        com.fasterxml.jackson.core.JsonProcessingException.class,
        IllegalArgumentException.class);

    return errorHandler;
  }
}
