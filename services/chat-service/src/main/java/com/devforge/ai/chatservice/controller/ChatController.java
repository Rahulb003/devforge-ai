package com.devforge.ai.chatservice.controller;

import com.devforge.ai.chatservice.dto.ChatDtos.MessageResponse;
import com.devforge.ai.chatservice.dto.ChatDtos.PostMessageRequest;
import com.devforge.ai.chatservice.service.ChatService;
import com.devforge.ai.common.web.ApiResponse;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A project's chat channel.
 *
 * <p>REST with polling. Real-time delivery over WebSockets is not implemented; a client polls with
 * {@code after} to fetch only what is new.
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/projects/{projectId}/chat/messages")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ChatController {

  private final ChatService chat;

  @GetMapping
  public ResponseEntity<ApiResponse<List<MessageResponse>>> list(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant before,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant after,
      @RequestParam(defaultValue = "50") int limit) {
    return ResponseEntity.ok(
        new ApiResponse<>(true, chat.list(organizationId, projectId, before, after, limit), null));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<MessageResponse>> post(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @Valid @RequestBody PostMessageRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(
        new ApiResponse<>(true, chat.post(organizationId, projectId, request.body()), null));
  }

  @PatchMapping("/{messageId}")
  public ResponseEntity<ApiResponse<MessageResponse>> edit(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID messageId,
      @Valid @RequestBody PostMessageRequest request) {
    return ResponseEntity.ok(new ApiResponse<>(
        true, chat.edit(organizationId, projectId, messageId, request.body()), null));
  }

  @DeleteMapping("/{messageId}")
  public ResponseEntity<ApiResponse<Void>> delete(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID messageId) {
    chat.delete(organizationId, projectId, messageId);
    return ResponseEntity.ok(new ApiResponse<>(true, null, "Message deleted"));
  }
}
