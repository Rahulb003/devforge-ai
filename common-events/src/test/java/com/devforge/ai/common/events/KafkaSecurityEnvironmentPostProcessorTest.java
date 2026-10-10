package com.devforge.ai.common.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devforge.ai.common.events.config.KafkaSecurityEnvironmentPostProcessor;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

@DisplayName("Kafka security settings")
class KafkaSecurityEnvironmentPostProcessorTest {

  private static StandardEnvironment process(Map<String, Object> settings) {
    var env = new StandardEnvironment();
    env.getPropertySources().addFirst(new MapPropertySource("test", settings));
    new KafkaSecurityEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());
    return env;
  }

  @Test
  @DisplayName("adds no credentials when none are configured")
  void noCredentialsNoChange() {
    assertThat(process(Map.of()).getProperty("spring.kafka.properties[security.protocol]")).isNull();
  }

  @Test
  @DisplayName("listeners retry an authorization failure instead of stopping for good")
  void authFailuresAreRetried() {
    // Set whether or not credentials are: a refused topic can happen either way.
    assertThat(process(Map.of()).getProperty("spring.kafka.listener.auth-exception-retry-interval"))
        .isEqualTo("10s");
    // Lowest precedence, so a service can still choose its own.
    assertThat(process(Map.of("spring.kafka.listener.auth-exception-retry-interval", "3s"))
        .getProperty("spring.kafka.listener.auth-exception-retry-interval")).isEqualTo("3s");
  }

  @Test
  @DisplayName("consumers look for newly created topics within thirty seconds")
  void metadataIsRefreshedOften() {
    assertThat(process(Map.of()).getProperty("spring.kafka.consumer.properties.metadata.max.age.ms"))
        .isEqualTo("30000");
  }

  @Test
  @DisplayName("refuses to start when authentication is required but missing")
  void requiredButMissing() {
    // Otherwise a missing secret quietly means talking to the broker unauthenticated.
    assertThatThrownBy(() -> process(Map.of("devforge.kafka.require-authentication", "true")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("does not demand credentials where Kafka is switched off")
  void requiredIgnoredWithoutKafka() {
    process(Map.of("devforge.kafka.require-authentication", "true", "devforge.kafka.enabled", "false"));
  }

  @Test
  @DisplayName("refuses a username without a password")
  void usernameWithoutPassword() {
    assertThatThrownBy(() -> process(Map.of("devforge.kafka.sasl.username", "svc")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("defaults to SCRAM over TLS")
  void secureDefaults() {
    var env = process(Map.of("devforge.kafka.sasl.username", "svc", "devforge.kafka.sasl.password", "pw"));

    assertThat(env.getProperty("spring.kafka.properties[security.protocol]")).isEqualTo("SASL_SSL");
    assertThat(env.getProperty("spring.kafka.properties[sasl.mechanism]")).isEqualTo("SCRAM-SHA-512");
    assertThat(env.getProperty("spring.kafka.properties[sasl.jaas.config]"))
        .startsWith("org.apache.kafka.common.security.scram.ScramLoginModule required");
  }

  @Test
  @DisplayName("a quote in the password cannot add options to the JAAS line")
  void passwordCannotInjectOptions() {
    var env = process(Map.of(
        "devforge.kafka.sasl.username", "svc",
        "devforge.kafka.sasl.password", "x\" evil=\"1"));

    assertThat(env.getProperty("spring.kafka.properties[sasl.jaas.config]"))
        .endsWith("password=\"x\\\" evil=\\\"1\";");
  }

  @Test
  @DisplayName("rejects an unknown mechanism at startup")
  void unknownMechanism() {
    assertThatThrownBy(() -> process(Map.of(
            "devforge.kafka.sasl.username", "svc",
            "devforge.kafka.sasl.password", "pw",
            "devforge.kafka.sasl.mechanism", "GSSAPI")))
        .isInstanceOf(IllegalStateException.class);
  }
}
