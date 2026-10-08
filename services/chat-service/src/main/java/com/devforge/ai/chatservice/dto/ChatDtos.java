package com.devforge.ai.chatservice.dto;

import com.devforge.ai.chatservice.entity.ChatMessageEntity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class ChatDtos {

  private ChatDtos() {}

  public record PostMessageRequest(@NotBlank @Size(max = 4000) String body) {}

  /** @param body null once deleted: a deleted message must not stay readable. */
  public record MessageResponse(
      UUID id,
      UUID authorId,
      String authorName,
      String body,
      boolean deleted,
      boolean edited,
      Instant createdAt) {

    public static MessageResponse from(ChatMessageEntity e) {
      return new MessageResponse(
          e.getId(), e.getAuthorId(), e.getAuthorName(),
          e.isDeleted() ? null : e.getBody(),
          e.isDeleted(), e.getEditedAt() != null, e.getCreatedAt());
    }
  }
}
