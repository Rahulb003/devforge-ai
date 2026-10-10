package com.devforge.ai.common.events.config;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Turns {@code devforge.kafka.sasl.*} into SASL settings for every Kafka client a service creates.
 *
 * <p>The broker had no authentication, so anything that could reach it could read every tenant's
 * events and inject forged ones that consumers accept as fact. Credentials are applied through
 * {@code spring.kafka.properties}, which Boot hands to producers, consumers <em>and</em> the admin
 * client alike; configuring only the producer and consumer would leave topic creation failing
 * against an authenticated broker.
 *
 * <p>Done here, once, rather than in eleven application.yml files that would drift. The JAAS line
 * is built in code because a password containing a quote or backslash would otherwise break it, or
 * worse, inject options into it.
 *
 * <p>Added at the lowest precedence, so an explicit {@code spring.kafka.properties} entry still
 * wins for anyone who needs something unusual.
 */
public class KafkaSecurityEnvironmentPostProcessor implements EnvironmentPostProcessor {

  static final String SOURCE_NAME = "devforgeKafkaSecurity";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
    var username = env.getProperty("devforge.kafka.sasl.username", "");
    var required = env.getProperty("devforge.kafka.require-authentication", Boolean.class, false);
    // Only meaningful where Kafka is used at all; the standalone profile runs without a broker.
    var kafkaEnabled = env.getProperty("devforge.kafka.enabled", Boolean.class, true);

    // Without a retry interval, an authorization failure is fatal to a listener: Spring Kafka stops
    // the container for good. The first Kubernetes deployment showed it - the consumers started
    // before the setup Job had granted their topics, were refused once, and never consumed again
    // while every health check stayed green. A permission change or broker restart in production
    // would do the same. Retried instead, the listener resumes once the grant exists.
    //
    // That alone was not enough: the retried consumer then joined its group before the Job had
    // created the topics, was assigned no partitions, and would not have looked again for the
    // default five minutes of metadata age. Thirty seconds bounds how long a consumer sits idle
    // beside a topic that has just appeared, at the cost of one small metadata request.
    env.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME + "-listener", Map.of(
        "spring.kafka.listener.auth-exception-retry-interval", "10s",
        "spring.kafka.consumer.properties.metadata.max.age.ms", "30000")));

    if (username.isBlank()) {
      if (required && kafkaEnabled) {
        // Fail at startup, not by silently talking to the broker unauthenticated.
        throw new IllegalStateException(
            "devforge.kafka.require-authentication is set but devforge.kafka.sasl.username is not");
      }
      return;
    }

    var password = env.getProperty("devforge.kafka.sasl.password", "");
    if (password.isBlank()) {
      throw new IllegalStateException("devforge.kafka.sasl.username is set without a password");
    }
    var mechanism = env.getProperty("devforge.kafka.sasl.mechanism", "SCRAM-SHA-512");
    // SASL_SSL by default: SASL over plain TCP sends PLAIN passwords in the clear, and even SCRAM
    // leaves every event readable on the wire.
    var protocol = env.getProperty("devforge.kafka.security-protocol", "SASL_SSL");

    Map<String, Object> props = new HashMap<>();
    props.put("spring.kafka.properties[security.protocol]", protocol);
    props.put("spring.kafka.properties[sasl.mechanism]", mechanism);
    props.put("spring.kafka.properties[sasl.jaas.config]", jaasConfig(mechanism, username, password));
    // The CA that signed the broker's certificate, as a PEM file, for a broker whose certificate a
    // public CA did not sign - the compose and Kubernetes stacks generate their own. Unset, the
    // JVM's default trust store decides. Hostname verification stays on either way.
    var truststore = env.getProperty("devforge.kafka.ssl.truststore-location", "");
    if (!truststore.isBlank()) {
      props.put("spring.kafka.properties[ssl.truststore.type]", "PEM");
      props.put("spring.kafka.properties[ssl.truststore.location]", truststore);
    }
    env.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, props));
  }

  static String jaasConfig(String mechanism, String username, String password) {
    var module = switch (mechanism) {
      case "PLAIN" -> "org.apache.kafka.common.security.plain.PlainLoginModule";
      case "SCRAM-SHA-256", "SCRAM-SHA-512" -> "org.apache.kafka.common.security.scram.ScramLoginModule";
      default -> throw new IllegalStateException("Unsupported Kafka SASL mechanism: " + mechanism);
    };
    return module + " required username=\"" + quote(username) + "\" password=\"" + quote(password)
        + "\";";
  }

  /** JAAS quoted-string escaping: backslash first, so the quote escapes are not doubled. */
  private static String quote(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
