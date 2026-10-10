package com.devforge.ai.authservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A long-lived credential for git clients. The token itself is never stored, only its hash. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "personal_access_tokens")
public class PersonalAccessTokenEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  // A plain id rather than a relation: nothing here needs the user loaded, and the exchange path
  // looks the user up itself so it sees the account's current status.
  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "name", nullable = false, length = 100)
  private String name;

  @Column(name = "token_hash", nullable = false, unique = true, updatable = false, length = 64)
  private String tokenHash;

  @Column(name = "token_prefix", nullable = false, updatable = false, length = 16)
  private String tokenPrefix;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "expires_at", nullable = false, updatable = false)
  private Instant expiresAt;

  @Column(name = "last_used_at")
  private Instant lastUsedAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  public boolean isUsableAt(Instant now) {
    return revokedAt == null && expiresAt.isAfter(now);
  }

  @PrePersist
  protected void onCreate() {
    if (id == null) id = UUID.randomUUID();
    if (createdAt == null) createdAt = Instant.now();
  }
}
