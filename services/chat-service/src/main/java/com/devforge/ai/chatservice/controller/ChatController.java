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
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * A project's chat channel.
 *
 * <p>Posting is REST; live delivery is Server-Sent Events on {@code /stream}. SSE rather than
 * WebSockets because delivery only flows server-to-client, and it travels through the gateway as an
 * ordinary HTTP response. Clients read it with {@code fetch}, not {@code EventSource}, because
 * {@code EventSource} cannot send an Authorization header and the alternative - the token in the
 * URL - would leak it into access logs.
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

  /** A live stream of this channel. Closes when the access token would expire; reconnect then. */
  @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(@PathVariable UUID organizationId, @PathVariable UUID projectId) {
    return chat.subscribe(organizationId, projectId);
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
