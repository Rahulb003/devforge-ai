package com.devforge.ai.common.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devforge.ai.common.events.config.KafkaSecurityEnvironmentPostProcessor;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import kafka.testkit.KafkaClusterTestKit;
import kafka.testkit.TestKitNodes;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * The client credentials, against a real broker that requires SASL.
 *
 * <p>The configuration is built exactly as a service builds it — through the environment post
 * processor — so this fails if the JAAS line is malformed, the escaping is wrong, or the settings
 * do not reach the client.
 */
@DisplayName("Kafka authentication against a real SASL broker")
class KafkaAuthenticationIntegrationTest {

  // A quote and a backslash: the characters that break a naively concatenated JAAS line.
  private static final String SERVICE_PASSWORD = "s3cr\"et\\pass";

  private static KafkaClusterTestKit cluster;

  @BeforeAll
  static void startBroker() throws Exception {
    var jaas = "org.apache.kafka.common.security.plain.PlainLoginModule required "
        + "username=\"admin\" password=\"admin-secret\" "
        + "user_admin=\"admin-secret\" "
        + "user_task-service=\"s3cr\\\"et\\\\pass\";";
    // Per-node properties, not Spring's EmbeddedKafkaKraftBroker: the test kit writes its own
    // PLAINTEXT protocol map over anything passed the ordinary way, and the first version of this
    // test "passed" its unauthenticated case against a broker that had no authentication at all.
    var nodes = new TestKitNodes.Builder()
        .setCombined(true)
        .setNumBrokerNodes(1)
        .setNumControllerNodes(1)
        .setPerServerProperties(Map.of(0, Map.of(
            "listener.security.protocol.map", "EXTERNAL:SASL_PLAINTEXT,CONTROLLER:PLAINTEXT",
            "sasl.enabled.mechanisms", "PLAIN",
            "sasl.mechanism.inter.broker.protocol", "PLAIN",
            "listener.name.external.plain.sasl.jaas.config", jaas,
            "auto.create.topics.enable", "true",
            "offsets.topic.replication.factor", "1")))
        .build();
    cluster = new KafkaClusterTestKit.Builder(nodes).build();
    cluster.format();
    cluster.startup();
    cluster.waitForReadyBrokers();
  }

  @AfterAll
  static void stopBroker() throws Exception {
    cluster.close();
  }

  /** What a service's Kafka clients receive, given these devforge.kafka.* settings. */
  private static Map<String, Object> clientConfig(Map<String, Object> settings) {
    var env = new StandardEnvironment();
    env.getPropertySources().addFirst(new MapPropertySource("test", settings));
    new KafkaSecurityEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());
    Map<String, Object> config = new HashMap<>(Binder.get(env)
        .bind("spring.kafka.properties", Bindable.mapOf(String.class, String.class))
        .orElse(Map.of()));
    config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, cluster.bootstrapServers());
    return config;
  }

  private static Map<String, Object> serviceCredentials(String password) {
    return Map.of(
        "devforge.kafka.sasl.username", "task-service",
        "devforge.kafka.sasl.password", password,
        "devforge.kafka.sasl.mechanism", "PLAIN",
        // Plain TCP only because the embedded broker has no certificate; the default is SASL_SSL.
        "devforge.kafka.security-protocol", "SASL_PLAINTEXT");
  }

  private static void send(Map<String, Object> config, String topic) throws Exception {
    var producerConfig = new HashMap<>(config);
    producerConfig.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    producerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    producerConfig.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
    try (var producer = new KafkaProducer<String, String>(producerConfig)) {
      producer.send(new ProducerRecord<>(topic, "k", "hello")).get(10, TimeUnit.SECONDS);
    }
  }

  @Test
  @DisplayName("a service with valid credentials publishes and consumes")
  void authenticatedRoundTrip() throws Exception {
    var config = clientConfig(serviceCredentials(SERVICE_PASSWORD));
    send(config, "devforge.auth-test.v1");

    var consumerConfig = new HashMap<>(config);
    consumerConfig.put(ConsumerConfig.GROUP_ID_CONFIG, "auth-test");
    consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    consumerConfig.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    consumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    try (var consumer = new KafkaConsumer<String, String>(consumerConfig)) {
      consumer.subscribe(List.of("devforge.auth-test.v1"));
      String received = null;
      for (int i = 0; i < 20 && received == null; i++) {
        for (var record : consumer.poll(Duration.ofMillis(500))) {
          received = record.value();
        }
      }
      assertThat(received).isEqualTo("hello");
    }
  }

  @Test
  @DisplayName("a client with no credentials cannot publish")
  void unauthenticatedRefused() {
    // What the broker allowed before: any process that could reach it.
    // A PLAINTEXT client never completes the handshake, so it cannot even fetch metadata; the
    // broker drops it rather than answering.
    assertThatThrownBy(() -> send(clientConfig(Map.of()), "devforge.auth-test.v1"))
        .hasCauseInstanceOf(org.apache.kafka.common.errors.TimeoutException.class)
        .hasMessageContaining("not present in metadata");
  }

  @Test
  @DisplayName("a wrong password is refused")
  void wrongPasswordRefused() {
    assertThatThrownBy(() -> send(clientConfig(serviceCredentials("guess")), "devforge.auth-test.v1"))
        .hasStackTraceContaining("Authentication failed");
  }
}
