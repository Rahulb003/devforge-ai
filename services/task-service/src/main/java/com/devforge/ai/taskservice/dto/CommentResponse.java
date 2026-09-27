package com.devforge.ai.taskservice.dto;

import java.time.Instant;
import java.util.UUID;

public record CommentResponse(
    UUID id, UUID taskId, UUID authorId, String body, Instant createdAt, Instant updatedAt) {}
