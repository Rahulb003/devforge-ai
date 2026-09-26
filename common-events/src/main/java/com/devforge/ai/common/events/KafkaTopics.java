package com.devforge.ai.common.events;

/**
 * Topic names and the retry/dead-letter naming convention.
 *
 * <p>Topics are grouped by bounded context rather than one per event type. A topic per event type
 * multiplies partitions and consumer groups for no ordering benefit, and events within a context
 * usually need to be consumed in order relative to one another.
 */
public final class KafkaTopics {

  private KafkaTopics() {}

  public static final String IDENTITY = "devforge.identity.v1";
  public static final String PROJECTS = "devforge.projects.v1";
  public static final String TASKS = "devforge.tasks.v1";
  public static final String SECURITY = "devforge.security.v1";
  public static final String NOTIFICATIONS = "devforge.notifications.v1";

  /**
   * Suffix appended by Spring Kafka's dead-letter recoverer.
   *
   * <p>The version is in the topic name so a breaking payload change ships as a new topic that
   * old and new consumers can straddle, rather than as an in-place change that breaks whoever
   * deploys second.
   */
  public static final String DLT_SUFFIX = ".dlt";

  public static String deadLetterTopicFor(String topic) {
    return topic + DLT_SUFFIX;
  }
}
