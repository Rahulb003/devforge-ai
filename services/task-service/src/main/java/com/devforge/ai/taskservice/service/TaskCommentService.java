package com.devforge.ai.taskservice.service;

import com.devforge.ai.common.exception.ResourceNotFoundException;
import com.devforge.ai.taskservice.dto.CommentResponse;
import com.devforge.ai.taskservice.dto.CreateCommentRequest;
import com.devforge.ai.taskservice.entity.TaskCommentEntity;
import com.devforge.ai.taskservice.repository.TaskCommentRepository;
import com.devforge.ai.taskservice.repository.TaskRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TaskCommentService {

  private final TaskCommentRepository commentRepository;
  private final TaskRepository taskRepository;
  private final TaskAccessService access;

  @Transactional
  public CommentResponse add(
      UUID organizationId, UUID projectId, UUID taskId, CreateCommentRequest request) {
    access.requireProjectAccess(organizationId, projectId);
    var user = access.requireCurrentUser();
    var task = loadTask(projectId, taskId);

    var comment = commentRepository.save(TaskCommentEntity.builder()
        .task(task)
        .authorId(user.id())
        .body(request.body())
        .build());

    return toResponse(comment);
  }

  @Transactional(readOnly = true)
  public List<CommentResponse> list(UUID organizationId, UUID projectId, UUID taskId) {
    access.requireProjectAccess(organizationId, projectId);
    loadTask(projectId, taskId);

    return commentRepository.findByTaskIdOrderByCreatedAtAsc(taskId).stream()
        .map(this::toResponse)
        .toList();
  }

  /**
   * Deletes a comment.
   *
   * <p>Only the author may delete their own. Project access is necessary but not sufficient here:
   * being able to see a task does not mean being able to remove someone else's words from it.
   * Moderation by a project admin is a separate capability and is not implemented yet.
   */
  @Transactional
  public void delete(UUID organizationId, UUID projectId, UUID taskId, UUID commentId) {
    access.requireProjectAccess(organizationId, projectId);
    var user = access.requireCurrentUser();
    loadTask(projectId, taskId);

    var comment = commentRepository.findByIdAndTaskId(commentId, taskId)
        .orElseThrow(() -> new ResourceNotFoundException("Comment not found"));

    if (!comment.getAuthorId().equals(user.id())) {
      throw new AccessDeniedException("Only the author can delete this comment");
    }
    commentRepository.delete(comment);
  }

  private com.devforge.ai.taskservice.entity.TaskEntity loadTask(UUID projectId, UUID taskId) {
    return taskRepository.findByIdAndProjectId(taskId, projectId)
        .orElseThrow(() -> new ResourceNotFoundException("Task not found"));
  }

  private CommentResponse toResponse(TaskCommentEntity comment) {
    return new CommentResponse(
        comment.getId(),
        comment.getTask().getId(),
        comment.getAuthorId(),
        comment.getBody(),
        comment.getCreatedAt(),
        comment.getUpdatedAt());
  }
}
