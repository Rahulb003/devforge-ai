package com.devforge.ai.common.events;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devforge.ai.common.events.config.KafkaSecurityEnvironmentPostProcessor;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import kafka.testkit.KafkaClusterTestKit;
import kafka.testkit.TestKitNodes;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
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
 * SASL over TLS against a real broker, with the client configured exactly as a service configures
 * it: through the environment post-processor and a PEM file naming the CA that signed the broker.
 *
 * <p>The broker's certificate comes from a throwaway CA in src/test/resources/kafka-tls, so the
 * cases that matter can be shown both ways: trusting that CA connects, and not trusting it fails
 * the handshake rather than quietly connecting anyway.
 */
@DisplayName("Kafka SASL over TLS against a real broker")
class KafkaTlsIntegrationTest {

  private static final String STORE_PASSWORD = "test-only-store-password";
  private static KafkaClusterTestKit cluster;
  private static String caPem;

  @BeforeAll
  static void startBroker() throws Exception {
    caPem = resource("kafka-tls/ca.pem");
    var jaas = "org.apache.kafka.common.security.plain.PlainLoginModule required "
        + "username=\"admin\" password=\"admin-secret\" "
        + "user_admin=\"admin-secret\" user_task-service=\"task-secret\";";
    var nodes = new TestKitNodes.Builder()
        .setCombined(true)
        .setNumBrokerNodes(1)
        .setNumControllerNodes(1)
        .setPerServerProperties(Map.of(0, Map.ofEntries(
            Map.entry("listener.security.protocol.map", "EXTERNAL:SASL_SSL,CONTROLLER:PLAINTEXT"),
            Map.entry("sasl.enabled.mechanisms", "PLAIN"),
            Map.entry("sasl.mechanism.inter.broker.protocol", "PLAIN"),
            Map.entry("listener.name.external.plain.sasl.jaas.config", jaas),
            Map.entry("ssl.keystore.type", "PKCS12"),
            Map.entry("ssl.keystore.location", resource("kafka-tls/broker.p12")),
            Map.entry("ssl.keystore.password", STORE_PASSWORD),
            Map.entry("ssl.key.password", STORE_PASSWORD),
            // The broker talks to itself over the same listener, so it must trust the CA too.
            Map.entry("ssl.truststore.type", "PEM"),
            Map.entry("ssl.truststore.location", caPem),
            Map.entry("auto.create.topics.enable", "true"),
            Map.entry("offsets.topic.replication.factor", "1"))))
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

  private static String resource(String name) throws Exception {
    return Path.of(KafkaTlsIntegrationTest.class.getClassLoader().getResource(name).toURI()).toString();
  }

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

  private static Map<String, Object> service(String protocol, String truststore) {
    var settings = new HashMap<String, Object>(Map.of(
        "devforge.kafka.sasl.username", "task-service",
        "devforge.kafka.sasl.password", "task-secret",
        "devforge.kafka.sasl.mechanism", "PLAIN",
        "devforge.kafka.security-protocol", protocol));
    if (truststore != null) {
      settings.put("devforge.kafka.ssl.truststore-location", truststore);
    }
    return settings;
  }

  private static void send(Map<String, Object> config) throws Exception {
    var producerConfig = new HashMap<>(config);
    producerConfig.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    producerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    producerConfig.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
    try (var producer = new KafkaProducer<String, String>(producerConfig)) {
      producer.send(new ProducerRecord<>("devforge.tls-test.v1", "k", "over tls")).get(10, TimeUnit.SECONDS);
    }
  }

  @Test
  @DisplayName("a service that trusts the stack's CA publishes over SASL_SSL")
  void trustedCaConnects() {
    assertThatCode(() -> send(clientConfig(service("SASL_SSL", caPem)))).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("without the CA the handshake fails; the client does not connect regardless")
  void untrustedCertificateIsRefused() {
    // The JVM's default trust store knows nothing of this CA, which is exactly the case of an
    // attacker presenting their own certificate.
    assertThatThrownBy(() -> send(clientConfig(service("SASL_SSL", null))))
        .hasStackTraceContaining("SSL handshake failed");
  }

  @Test
  @DisplayName("a client speaking plain SASL to the TLS listener gets nowhere")
  void plaintextClientIsRefused() {
    assertThatThrownBy(() -> send(clientConfig(service("SASL_PLAINTEXT", null))))
        .hasStackTraceContaining("not present in metadata");
  }
}
