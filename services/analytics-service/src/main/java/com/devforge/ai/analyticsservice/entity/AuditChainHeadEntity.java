package com.devforge.ai.analyticsservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Where one audit chain currently ends. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "audit_chain_head")
public class AuditChainHeadEntity {

  @Id
  @Column(name = "chain_key", nullable = false, updatable = false, length = 80)
  private String chainKey;

  @Column(name = "last_sequence", nullable = false)
  private long lastSequence;

  @Column(name = "last_hash", nullable = false, length = 64)
  private String lastHash;

  public AuditChainHeadEntity(String chainKey) {
    this.chainKey = chainKey;
    this.lastSequence = 0;
    this.lastHash = AuditChain.GENESIS;
  }

  public void advance(long sequence, String hash) {
    this.lastSequence = sequence;
    this.lastHash = hash;
  }
}
