package com.devforge.ai.documentationservice.dto;

import com.devforge.ai.documentationservice.entity.DocSetEntity;
import com.devforge.ai.documentationservice.entity.DocumentEntity;
import com.devforge.ai.documentationservice.generate.DocumentKind;
import com.devforge.ai.documentationservice.model.DocSetStatus;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Request and response shapes for documentation-service. */
public final class DocDtos {

  private DocDtos() {}

  /** @param ref what to document. Defaults to the repository's default branch. */
  public record GenerateRequest(@Size(max = 255) String ref) {}

  public record DocSetResponse(
      UUID id,
      UUID repositoryId,
      String ref,
      DocSetStatus status,
      int filesScanned,
      String failureReason,
      UUID generatedBy,
      Instant createdAt,
      Instant completedAt) {

    public static DocSetResponse from(DocSetEntity entity) {
      return new DocSetResponse(
          entity.getId(),
          entity.getRepositoryId(),
          entity.getRef(),
          entity.getStatus(),
          entity.getFilesScanned(),
          entity.getFailureReason(),
          entity.getGeneratedBy(),
          entity.getCreatedAt(),
          entity.getCompletedAt());
    }
  }

  /**
   * @param content Markdown, or null in a listing. A set of three documents is a lot of Markdown to
   *     send when the caller only wants to know which exist.
   */
  public record DocumentResponse(
      UUID id, DocumentKind kind, String title, String content, Instant createdAt) {

    public static DocumentResponse summary(DocumentEntity entity) {
      return new DocumentResponse(
          entity.getId(), entity.getKind(), entity.getTitle(), null, entity.getCreatedAt());
    }

    public static DocumentResponse full(DocumentEntity entity) {
      return new DocumentResponse(
          entity.getId(), entity.getKind(), entity.getTitle(), entity.getContent(),
          entity.getCreatedAt());
    }
  }
}
