package com.devforge.ai.documentationservice.entity;

import com.devforge.ai.documentationservice.generate.DocumentKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/** One rendered document within a set. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "documents")
public class DocumentEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  /**
   * Plain column rather than a {@code @ManyToOne}.
   *
   * <p>A document is only ever read through its set, so the association would buy nothing.
   */
  @Column(name = "doc_set_id", nullable = false, updatable = false)
  private UUID docSetId;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 40)
  private DocumentKind kind;

  @Column(name = "title", nullable = false, length = 200)
  private String title;

  /**
   * Rendered Markdown, stored rather than regenerated on read.
   *
   * <p>A document describes the repository at one ref. Regenerating it later would quietly answer a
   * different question with the same URL.
   */
  @Column(name = "content", nullable = false, columnDefinition = "TEXT")
  private String content;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @PrePersist
  void onCreate() {
    if (id == null) {
      id = UUID.randomUUID();
    }
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }
}
