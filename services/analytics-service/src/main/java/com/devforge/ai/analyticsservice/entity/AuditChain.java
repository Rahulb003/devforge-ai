package com.devforge.ai.analyticsservice.entity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

/**
 * The hashing behind the audit chain, kept in one place so writing and verifying cannot disagree.
 *
 * <p>The hash covers every recorded field plus the chain position and the previous hash. It is
 * not a signature: someone who can write the database can recompute a whole chain. What it does
 * guarantee is that a change cannot be made quietly, because the chain head must then change too,
 * and the head hash the verify endpoint returns can be recorded somewhere the database's owner
 * does not control.
 */
public final class AuditChain {

  /** The "previous hash" of the first entry in every chain. */
  public static final String GENESIS = "0".repeat(64);

  private AuditChain() {}

  /** Project entries chain per project; organization-level entries per organization. */
  public static String key(UUID organizationId, UUID projectId) {
    return (organizationId == null ? "-" : organizationId) + "/"
        + (projectId == null ? "-" : projectId);
  }

  /**
   * The precision the database keeps. PostgreSQL stores microseconds, so hashing nanoseconds
   * would make every entry written on a JVM with a nanosecond clock fail verification.
   */
  public static Instant storable(Instant instant) {
    return instant.truncatedTo(ChronoUnit.MICROS);
  }

  public static String hash(String previousHash, long sequence, AuditEntryEntity entry) {
    var occurred = entry.getOccurredAt();
    var micros = occurred.getEpochSecond() * 1_000_000L + occurred.getNano() / 1_000;
    // Each field length-prefixed, so no choice of values can make two different entries encode the
    // same way - "ab" + "c" and "a" + "bc" would collide under plain concatenation.
    var canonical = new StringBuilder();
    for (var field : new Object[] {
        previousHash, sequence, entry.getChainKey(), entry.getEventId(), entry.getEventType(),
        entry.getSource(), entry.getOrganizationId(), entry.getProjectId(), entry.getActorId(),
        micros, entry.getDetails()}) {
      var value = field == null ? "" : field.toString();
      canonical.append(value.length()).append(':').append(value).append(';');
    }
    try {
      var digest = MessageDigest.getInstance("SHA-256")
          .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is required of every JVM", ex);
    }
  }
}
