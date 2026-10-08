package com.devforge.ai.chatservice.repository;

import com.devforge.ai.chatservice.entity.ChatMessageEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Messages, always scoped by project so an id from another channel cannot resolve. */
public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, UUID> {

  List<ChatMessageEntity> findByProjectIdOrderByCreatedAtDescIdDesc(UUID projectId, Pageable page);

  List<ChatMessageEntity> findByProjectIdAndCreatedAtBeforeOrderByCreatedAtDescIdDesc(
      UUID projectId, Instant before, Pageable page);

  /** For polling: only what arrived after the newest message the client already has. */
  List<ChatMessageEntity> findByProjectIdAndCreatedAtAfterOrderByCreatedAtAscIdAsc(
      UUID projectId, Instant after, Pageable page);

  Optional<ChatMessageEntity> findByIdAndProjectId(UUID id, UUID projectId);
}
