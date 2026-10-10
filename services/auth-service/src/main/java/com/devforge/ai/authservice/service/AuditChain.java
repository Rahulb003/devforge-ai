package com.devforge.ai.authservice.service;

import com.devforge.ai.authservice.entity.AuditLogEntity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

/**
 * The hashing behind the account audit chain. The same construction as analytics-service's
 * project chain: every field length-prefixed, chained on the previous hash, timestamps at the
 * microsecond precision PostgreSQL keeps.
 */
final class AuditChain {

  static final String KEY = "accounts";
  static final String GENESIS = "0".repeat(64);

  private AuditChain() {}

  static Instant storable(Instant instant) {
    return instant.truncatedTo(ChronoUnit.MICROS);
  }

  static String hash(String previousHash, long sequence, AuditLogEntity entry) {
    var at = entry.getCreatedAt();
    var micros = at.getEpochSecond() * 1_000_000L + at.getNano() / 1_000;
    var canonical = new StringBuilder();
    for (var field : new Object[] {
        previousHash, sequence, entry.getUser() == null ? null : entry.getUser().getId(),
        entry.getAction(), entry.getEntityType(), entry.getEntityId(), entry.getIpAddress(),
        entry.getDetails(), micros}) {
      var value = field == null ? "" : field.toString();
      canonical.append(value.length()).append(':').append(value).append(';');
    }
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is required of every JVM", ex);
    }
  }
}
