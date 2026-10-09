package com.devforge.ai.gitservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Metadata for one hosted repository. The git objects live on disk.
 *
 * <p>Deliberately does <em>not</em> extend {@code BaseEntity}, which brings a {@code deleted_at}
 * column and with it soft deletion. A soft-deleted repository is a row that claims the repository
 * still exists while its files have been removed — and keeping the files instead means storage grows
 * for ever with data the user believes they deleted. Deletion here removes the row and the directory
 * together.
 *
 * <p>There is also no {@code storage_path}. The on-disk location is derived from ids at read time,
 * so no stored value can be edited to point the service at an arbitrary directory, and a rename
 * never has to move files.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "repositories")
public class RepositoryEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "project_id", nullable = false, updatable = false)
  private UUID projectId;

  @Column(name = "organization_id", nullable = false, updatable = false)
  private UUID organizationId;

  @Column(name = "name", nullable = false, length = 100)
  private String name;

  @Column(name = "description", length = 1000)
  private String description;

  @Column(name = "default_branch", nullable = false, length = 255)
  private String defaultBranch;

  @Column(name = "created_by", nullable = false, updatable = false)
  private UUID createdBy;

  /** Approvals of the current changes a pull request needs to merge. Changed only by an admin. */
  @Builder.Default
  @Column(name = "required_approvals", nullable = false)
  private int requiredApprovals = 0;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at")
  private Instant updatedAt;

  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  /**
   * The id is assigned here rather than by {@code @UuidGenerator}.
   *
   * <p>The caller needs it to build the on-disk path in the same transaction that creates the row,
   * and an entity using {@code @UuidGenerator} must never have its id set in application code —
   * doing so makes Hibernate treat the instance as detached and silently turn {@code persist()} into
   * {@code merge()} (AD-7).
   */
  @PrePersist
  void onCreate() {
    if (id == null) {
      id = UUID.randomUUID();
    }
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }
}
