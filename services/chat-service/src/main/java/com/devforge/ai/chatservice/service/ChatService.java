package com.devforge.ai.chatservice.service;

import com.devforge.ai.chatservice.dto.ChatDtos.MessageResponse;
import com.devforge.ai.chatservice.entity.ChatMessageEntity;
import com.devforge.ai.chatservice.repository.ChatMessageRepository;
import com.devforge.ai.common.exception.ResourceConflictException;
import com.devforge.ai.common.exception.ResourceNotFoundException;
import com.devforge.ai.common.security.AuthenticatedUser;
import com.devforge.ai.common.security.client.ProjectAccessClient;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * A project's channel. Anyone who can see the project can read and post; only the author may edit
 * or delete their own message.
 */
@Service
@RequiredArgsConstructor
public class ChatService {

  private static final int MAX_PAGE = 100;

  private final ChatMessageRepository messages;
  private final ProjectAccessClient projectAccess;
  private final ChatStreamRegistry streams;

  /** Opens a live stream of this channel. Access is checked here, once, when it opens. */
  public SseEmitter subscribe(UUID organizationId, UUID projectId) {
    requireAccess(organizationId, projectId);
    return streams.subscribe(projectId);
  }

  /**
   * Newest first, or — with {@code after} — only messages newer than the client's latest, oldest
   * first, which is what a polling client needs to append without re-sorting.
   */
  @Transactional(readOnly = true)
  public List<MessageResponse> list(
      UUID organizationId, UUID projectId, Instant before, Instant after, int limit) {
    requireAccess(organizationId, projectId);
    var page = PageRequest.of(0, Math.min(Math.max(limit, 1), MAX_PAGE));
    List<ChatMessageEntity> rows;
    if (after != null) {
      rows = messages.findByProjectIdAndCreatedAtAfterOrderByCreatedAtAscIdAsc(projectId, after, page);
    } else if (before != null) {
      rows = messages.findByProjectIdAndCreatedAtBeforeOrderByCreatedAtDescIdDesc(
          projectId, before, page);
    } else {
      rows = messages.findByProjectIdOrderByCreatedAtDescIdDesc(projectId, page);
    }
    return rows.stream().map(MessageResponse::from).toList();
  }

  @Transactional
  public MessageResponse post(UUID organizationId, UUID projectId, String body) {
    requireAccess(organizationId, projectId);
    var user = currentUser();
    var saved = MessageResponse.from(messages.save(ChatMessageEntity.builder()
        .projectId(projectId)
        .organizationId(organizationId)
        .authorId(user.id())
        .authorName(user.username())
        .body(body.strip())
        .build()));
    broadcastAfterCommit(projectId, saved);
    return saved;
  }

  @Transactional
  public MessageResponse edit(UUID organizationId, UUID projectId, UUID messageId, String body) {
    var message = loadOwn(organizationId, projectId, messageId);
    if (message.isDeleted()) {
      throw new ResourceConflictException("A deleted message cannot be edited");
    }
    message.setBody(body.strip());
    message.setEditedAt(Instant.now());
    var saved = MessageResponse.from(messages.save(message));
    broadcastAfterCommit(projectId, saved);
    return saved;
  }

  @Transactional
  public void delete(UUID organizationId, UUID projectId, UUID messageId) {
    var message = loadOwn(organizationId, projectId, messageId);
    if (message.isDeleted()) {
      return;
    }
    // The body is overwritten, not just flagged: a soft delete that keeps the text is a delete in
    // the UI only, and the words remain for anyone reading the database.
    message.setBody("");
    message.setDeletedAt(Instant.now());
    broadcastAfterCommit(projectId, MessageResponse.from(messages.save(message)));
  }

  /**
   * Pushes to open streams only once the transaction has committed.
   *
   * <p>Sending inside the transaction would push a message that a later failure rolls back: every
   * subscriber would see something that, on reload, never existed.
   */
  private void broadcastAfterCommit(UUID projectId, MessageResponse message) {
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        streams.broadcast(projectId, message);
      }
    });
  }

  /**
   * Loads a message the caller wrote.
   *
   * <p>403, not 404, for someone else's message. The usual 404 rule exists to avoid confirming an
   * id exists — but the caller can already see this message in the channel, so "not found" would
   * be a lie that hides nothing.
   */
  private ChatMessageEntity loadOwn(UUID organizationId, UUID projectId, UUID messageId) {
    requireAccess(organizationId, projectId);
    var message = messages.findByIdAndProjectId(messageId, projectId)
        .orElseThrow(() -> new ResourceNotFoundException("Message not found"));
    if (!message.getAuthorId().equals(currentUser().id())) {
      throw new AccessDeniedException("Only the author can change this message");
    }
    return message;
  }

  private void requireAccess(UUID organizationId, UUID projectId) {
    projectAccess.requireProjectAccess(organizationId, projectId, bearerToken());
  }

  private AuthenticatedUser currentUser() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
      return user;
    }
    throw new AccessDeniedException("Not authenticated");
  }

  private String bearerToken() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
      var header = attrs.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
      if (header != null && !header.isBlank()) {
        return header;
      }
    }
    throw new AccessDeniedException("No bearer token on the current request");
  }
}
