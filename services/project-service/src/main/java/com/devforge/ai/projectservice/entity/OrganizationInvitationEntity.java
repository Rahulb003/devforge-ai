package com.devforge.ai.projectservice.entity;

import com.devforge.ai.common.model.BaseEntity;
import com.devforge.ai.projectservice.model.OrganizationRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** An invitation to join an organization, addressed to an email address. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "organization_invitations")
public class OrganizationInvitationEntity extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "organization_id", nullable = false)
  private OrganizationEntity organization;

  @Column(name = "email", nullable = false, length = 255)
  private String email;

  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false, length = 50)
  private OrganizationRole role;

  @Column(name = "invited_by", nullable = false)
  private UUID invitedBy;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "accepted_at")
  private Instant acceptedAt;

  @Column(name = "declined_at")
  private Instant declinedAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  public boolean isPendingAt(Instant now) {
    return acceptedAt == null && declinedAt == null && revokedAt == null && expiresAt.isAfter(now);
  }
}
