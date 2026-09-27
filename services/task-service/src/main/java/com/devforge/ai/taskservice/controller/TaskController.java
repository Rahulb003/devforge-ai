package com.devforge.ai.taskservice.controller;

import com.devforge.ai.common.web.ApiResponse;
import com.devforge.ai.taskservice.dto.AssignTaskRequest;
import com.devforge.ai.taskservice.dto.BoardColumn;
import com.devforge.ai.taskservice.dto.CommentResponse;
import com.devforge.ai.taskservice.dto.CreateCommentRequest;
import com.devforge.ai.taskservice.dto.CreateTaskRequest;
import com.devforge.ai.taskservice.dto.MoveTaskRequest;
import com.devforge.ai.taskservice.dto.TaskResponse;
import com.devforge.ai.taskservice.dto.UpdateTaskRequest;
import com.devforge.ai.taskservice.service.TaskCommentService;
import com.devforge.ai.taskservice.service.TaskService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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
 * Tasks, addressed under their organization and project.
 *
 * <p>The route carries the tenant and the project for the same reason project-service's does: the
 * lookup is scoped by both, so an id from elsewhere cannot resolve. Whether the caller may touch
 * the project at all is decided by project-service, which owns that rule.
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/projects/{projectId}/tasks")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class TaskController {

  private final TaskService taskService;
  private final TaskCommentService commentService;

  @PostMapping
  public ResponseEntity<ApiResponse<TaskResponse>> create(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @Valid @RequestBody CreateTaskRequest request) {
    var created = taskService.create(organizationId, projectId, request);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(new ApiResponse<>(true, created, "Task created"));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<Page<TaskResponse>>> list(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PageableDefault(size = 50) Pageable pageable) {
    return ResponseEntity.ok(
        new ApiResponse<>(true, taskService.list(organizationId, projectId, pageable), null));
  }

  /** The whole board in one request, so a drag does not need a round trip per column. */
  @GetMapping("/board")
  public ResponseEntity<ApiResponse<List<BoardColumn>>> board(
      @PathVariable UUID organizationId, @PathVariable UUID projectId) {
    return ResponseEntity.ok(new ApiResponse<>(true, taskService.board(organizationId, projectId), null));
  }

  @GetMapping("/{taskId}")
  public ResponseEntity<ApiResponse<TaskResponse>> get(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID taskId) {
    return ResponseEntity.ok(
        new ApiResponse<>(true, taskService.get(organizationId, projectId, taskId), null));
  }

  @PatchMapping("/{taskId}")
  public ResponseEntity<ApiResponse<TaskResponse>> update(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID taskId,
      @Valid @RequestBody UpdateTaskRequest request) {
    return ResponseEntity.ok(new ApiResponse<>(
        true, taskService.update(organizationId, projectId, taskId, request), "Task updated"));
  }

  /** Moves a card between columns, or within one. */
  @PostMapping("/{taskId}/move")
  public ResponseEntity<ApiResponse<TaskResponse>> move(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID taskId,
      @Valid @RequestBody MoveTaskRequest request) {
    return ResponseEntity.ok(new ApiResponse<>(
        true, taskService.move(organizationId, projectId, taskId, request), "Task moved"));
  }

  @PostMapping("/{taskId}/assign")
  public ResponseEntity<ApiResponse<TaskResponse>> assign(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID taskId,
      @RequestBody AssignTaskRequest request) {
    return ResponseEntity.ok(new ApiResponse<>(
        true, taskService.assign(organizationId, projectId, taskId, request), "Task assigned"));
  }

  @DeleteMapping("/{taskId}")
  public ResponseEntity<ApiResponse<Void>> delete(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID taskId) {
    taskService.delete(organizationId, projectId, taskId);
    return ResponseEntity.ok(new ApiResponse<>(true, null, "Task deleted"));
  }

  @PostMapping("/{taskId}/labels")
  public ResponseEntity<ApiResponse<TaskResponse>> addLabel(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID taskId,
      @RequestParam String label) {
    return ResponseEntity.ok(new ApiResponse<>(
        true, taskService.addLabel(organizationId, projectId, taskId, label), "Label added"));
  }

  @DeleteMapping("/{taskId}/labels")
  public ResponseEntity<ApiResponse<TaskResponse>> removeLabel(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID taskId,
      @RequestParam String label) {
    return ResponseEntity.ok(new ApiResponse<>(
        true, taskService.removeLabel(organizationId, projectId, taskId, label), "Label removed"));
  }

  @GetMapping("/{taskId}/comments")
  public ResponseEntity<ApiResponse<List<CommentResponse>>> listComments(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID taskId) {
    return ResponseEntity.ok(
        new ApiResponse<>(true, commentService.list(organizationId, projectId, taskId), null));
  }

  @PostMapping("/{taskId}/comments")
  public ResponseEntity<ApiResponse<CommentResponse>> addComment(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID taskId,
      @Valid @RequestBody CreateCommentRequest request) {
    var created = commentService.add(organizationId, projectId, taskId, request);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(new ApiResponse<>(true, created, "Comment added"));
  }

  @DeleteMapping("/{taskId}/comments/{commentId}")
  public ResponseEntity<ApiResponse<Void>> deleteComment(
      @PathVariable UUID organizationId,
      @PathVariable UUID projectId,
      @PathVariable UUID taskId,
      @PathVariable UUID commentId) {
    commentService.delete(organizationId, projectId, taskId, commentId);
    return ResponseEntity.ok(new ApiResponse<>(true, null, "Comment deleted"));
  }
}
