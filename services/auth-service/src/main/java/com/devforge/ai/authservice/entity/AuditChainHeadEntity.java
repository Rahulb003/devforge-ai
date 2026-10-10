package com.devforge.ai.authservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Where the account audit chain currently ends. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "audit_chain_head")
public class AuditChainHeadEntity {

  @Id
  @Column(name = "chain_key", nullable = false, updatable = false, length = 40)
  private String chainKey;

  @Column(name = "last_sequence", nullable = false)
  private long lastSequence;

  @Column(name = "last_hash", nullable = false, length = 64)
  private String lastHash;
}
