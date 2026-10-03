package com.devforge.ai.common.events;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Boot entry point for the event-backbone tests.
 *
 * <p>{@code common-events} is a library and has no application class of its own, but the outbox,
 * the publisher and the idempotency guard are Spring beans backed by JPA, so verifying them needs
 * a real context. Declared here in test sources so nothing ships in the library jar.
 *
 * <p>Placed in {@code com.devforge.ai.common.events} so component scanning picks up {@code config}
 * and {@code outbox} beneath it. It deliberately does not scan {@code com.devforge.ai.common},
 * which would drag in common-library's web and OpenAPI configuration for no benefit here.
 */
@SpringBootApplication
public class EventBackboneTestApplication {}
