package com.devforge.ai.taskservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devforge.ai.common.events.outbox.OutboxEventRepository;
import com.devforge.ai.common.exception.ResourceNotFoundException;
import com.devforge.ai.common.security.client.ProjectAccessClient;
import com.devforge.ai.taskservice.repository.SprintRepository;
import com.devforge.ai.taskservice.repository.TaskCommentRepository;
import com.devforge.ai.taskservice.repository.TaskLabelRepository;
import com.devforge.ai.taskservice.repository.TaskNumberSequenceRepository;
import com.devforge.ai.taskservice.repository.TaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Task API behaviour, including cross-project isolation.
 *
 * <p>{@link ProjectAccessClient} is mocked: it is the boundary to another service, and these tests
 * are about this service's logic. The contract it stands in for is narrow and explicit — it throws
 * {@link ResourceNotFoundException} when the caller may not use the project — so the mock does not
 * paper over anything subtle.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TaskApiTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private TaskRepository taskRepository;
  @Autowired private TaskLabelRepository labelRepository;
  @Autowired private TaskCommentRepository commentRepository;
  @Autowired private TaskNumberSequenceRepository sequenceRepository;
  @Autowired private SprintRepository sprintRepository;
  @Autowired private OutboxEventRepository outboxRepository;

  @MockitoBean private ProjectAccessClient projectAccessClient;

  private final UUID organizationId = UUID.randomUUID();
  private final UUID projectId = UUID.randomUUID();
  private final UUID otherProjectId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();

  private String token;

  private String tasksUrl() {
    return "/api/v1/organizations/%s/projects/%s/tasks".formatted(organizationId, projectId);
  }

  private String sprintsUrl() {
    return "/api/v1/organizations/%s/projects/%s/sprints".formatted(organizationId, projectId);
  }

  @BeforeEach
  void setUp() {
    commentRepository.deleteAll();
    labelRepository.deleteAll();
    taskRepository.deleteAll();
    sprintRepository.deleteAll();
    sequenceRepository.deleteAll();
    outboxRepository.deleteAll();

    token = TestTokens.accessToken(userId);

    Mockito.reset(projectAccessClient);
    // Access to the project under test is granted; the other project is not.
    Mockito.doThrow(new ResourceNotFoundException("Project not found"))
        .when(projectAccessClient)
        .requireProjectAccess(any(), eq(otherProjectId), any());
  }

  private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
    return builder.header("Authorization", "Bearer " + token);
  }

  private JsonNode dataOf(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
  }

  private UUID createTask(String title) throws Exception {
    var body = objectMapper.writeValueAsString(Map.of("title", title));
    var result = mockMvc.perform(authed(post(tasksUrl()))
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andReturn();
    return UUID.fromString(dataOf(result).get("id").asText());
  }

  // --- Creation -----------------------------------------------------------

  @Nested
  @DisplayName("Creating tasks")
  class Creating {

    @Test
    @DisplayName("a task starts in the backlog with sensible defaults")
    void defaultsAreApplied() throws Exception {
      var body = objectMapper.writeValueAsString(Map.of("title", "First task"));
      mockMvc.perform(authed(post(tasksUrl()))
              .contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.data.status").value("BACKLOG"))
          .andExpect(jsonPath("$.data.priority").value("MEDIUM"))
          .andExpect(jsonPath("$.data.type").value("TASK"))
          .andExpect(jsonPath("$.data.taskNumber").value(1))
          // The creator is the reporter, taken from the token rather than the body.
          .andExpect(jsonPath("$.data.reporterId").value(userId.toString()));
    }

    @Test
    @DisplayName("task numbers increment per project and start from one")
    void taskNumbersIncrement() throws Exception {
      createTask("One");
      createTask("Two");
      createTask("Three");

      assertThat(taskRepository.findAll())
          .extracting(t -> t.getTaskNumber())
          .containsExactlyInAnyOrder(1, 2, 3);
    }

    @Test
    @DisplayName("a blank title is rejected")
    void blankTitleRejected() throws Exception {
      var body = objectMapper.writeValueAsString(Map.of("title", "   "));
      mockMvc.perform(authed(post(tasksUrl()))
              .contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a subtask cannot itself have subtasks")
    void subtaskNestingIsLimited() throws Exception {
      var parent = createTask("Parent");

      var childBody = objectMapper.writeValueAsString(
          Map.of("title", "Child", "parentTaskId", parent.toString()));
      var childResult = mockMvc.perform(authed(post(tasksUrl()))
              .contentType(MediaType.APPLICATION_JSON).content(childBody))
          .andExpect(status().isCreated())
          .andReturn();
      var childId = dataOf(childResult).get("id").asText();

      // Arbitrary depth makes ordering and rollup ambiguous, so it is refused.
      var grandchildBody = objectMapper.writeValueAsString(
          Map.of("title", "Grandchild", "parentTaskId", childId));
      mockMvc.perform(authed(post(tasksUrl()))
              .contentType(MediaType.APPLICATION_JSON).content(grandchildBody))
          .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("creating a task stages a TaskCreated event")
    void creationStagesEvent() throws Exception {
      createTask("Eventful");

      assertThat(outboxRepository.findAll())
          .extracting(e -> e.getEventType())
          .contains("TaskCreated");
    }
  }

  // --- The board ----------------------------------------------------------

  @Nested
  @DisplayName("The board")
  class Board {

    @Test
    @DisplayName("every status is returned, including empty columns")
    void allColumnsPresent() throws Exception {
      // A board that hides empty columns has nowhere to drop the first card.
      mockMvc.perform(authed(get(tasksUrl() + "/board")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.length()").value(6));
    }

    @Test
    @DisplayName("moving a task changes its column and records completion")
    void moveChangesColumn() throws Exception {
      var taskId = createTask("Movable");

      var body = objectMapper.writeValueAsString(Map.of("status", "DONE"));
      mockMvc.perform(authed(post(tasksUrl() + "/" + taskId + "/move"))
              .contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.status").value("DONE"))
          // Stored rather than derived, because cycle time is read far more than written.
          .andExpect(jsonPath("$.data.completedAt").isNotEmpty());
    }

    @Test
    @DisplayName("reopening a completed task clears its completion time")
    void reopeningClearsCompletion() throws Exception {
      var taskId = createTask("Reopened");

      var done = objectMapper.writeValueAsString(Map.of("status", "DONE"));
      mockMvc.perform(authed(post(tasksUrl() + "/" + taskId + "/move"))
          .contentType(MediaType.APPLICATION_JSON).content(done));

      var reopen = objectMapper.writeValueAsString(Map.of("status", "IN_PROGRESS"));
      mockMvc.perform(authed(post(tasksUrl() + "/" + taskId + "/move"))
              .contentType(MediaType.APPLICATION_JSON).content(reopen))
          .andExpect(status().isOk())
          // A stale completion time would inflate every cycle-time figure.
          .andExpect(jsonPath("$.data.completedAt").doesNotExist());
    }

    @Test
    @DisplayName("positions stay contiguous from zero within a column")
    void positionsStayContiguous() throws Exception {
      var first = createTask("A");
      var second = createTask("B");
      var third = createTask("C");

      // Drop the third card at the very top of the column.
      var body = objectMapper.writeValueAsString(Map.of("status", "BACKLOG", "position", 0));
      mockMvc.perform(authed(post(tasksUrl() + "/" + third + "/move"))
              .contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isOk());

      var backlog = taskRepository.findByProjectIdAndStatusOrderByBoardPositionAsc(
          projectId, com.devforge.ai.taskservice.model.TaskStatus.BACKLOG);

      assertThat(backlog).extracting(t -> t.getBoardPosition()).containsExactly(0, 1, 2);
      assertThat(backlog.get(0).getId()).isEqualTo(third);
      assertThat(backlog).extracting(t -> t.getId()).containsExactly(third, first, second);
    }

    @Test
    @DisplayName("a position beyond the end of a column appends instead of failing")
    void outOfRangePositionIsClamped() throws Exception {
      var taskId = createTask("Only");

      var body = objectMapper.writeValueAsString(Map.of("status", "TODO", "position", 999));
      mockMvc.perform(authed(post(tasksUrl() + "/" + taskId + "/move"))
              .contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.boardPosition").value(0));
    }

    @Test
    @DisplayName("the source column is renumbered after a card leaves it")
    void sourceColumnIsRenumbered() throws Exception {
      var first = createTask("A");
      createTask("B");
      createTask("C");

      var body = objectMapper.writeValueAsString(Map.of("status", "IN_PROGRESS"));
      mockMvc.perform(authed(post(tasksUrl() + "/" + first + "/move"))
          .contentType(MediaType.APPLICATION_JSON).content(body));

      var backlog = taskRepository.findByProjectIdAndStatusOrderByBoardPositionAsc(
          projectId, com.devforge.ai.taskservice.model.TaskStatus.BACKLOG);

      // Leaving a hole would let positions drift apart over time.
      assertThat(backlog).extracting(t -> t.getBoardPosition()).containsExactly(0, 1);
    }
  }

  // --- Isolation ----------------------------------------------------------

  @Nested
  @DisplayName("Access control")
  class Access {

    @Test
    @DisplayName("an anonymous request is refused")
    void anonymousRefused() throws Exception {
      mockMvc.perform(get(tasksUrl())).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a refresh token cannot be used as an API credential")
    void refreshTokenRefused() throws Exception {
      mockMvc.perform(get(tasksUrl())
              .header("Authorization", "Bearer " + TestTokens.refreshToken(userId)))
          .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a forged token is refused")
    void forgedTokenRefused() throws Exception {
      mockMvc.perform(get(tasksUrl())
              .header("Authorization", "Bearer " + TestTokens.wronglySignedToken(userId)))
          .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an expired token is refused")
    void expiredTokenRefused() throws Exception {
      mockMvc.perform(get(tasksUrl())
              .header("Authorization", "Bearer " + TestTokens.expiredToken(userId)))
          .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a project the caller cannot access reports 404")
    void inaccessibleProjectIsNotFound() throws Exception {
      var url = "/api/v1/organizations/%s/projects/%s/tasks".formatted(organizationId, otherProjectId);

      // 404 rather than 403, matching project-service: telling them apart would
      // let a caller probe project ids across tenants.
      mockMvc.perform(authed(get(url))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a task id from another project cannot be read through an accessible project")
    void foreignTaskIdIsNotFound() throws Exception {
      // The classic IDOR attempt: keep a project you may use, swap in a task id
      // that belongs elsewhere.
      var foreignTaskId = UUID.randomUUID();
      mockMvc.perform(authed(get(tasksUrl() + "/" + foreignTaskId)))
          .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a task cannot be moved through a project it does not belong to")
    void foreignTaskCannotBeMoved() throws Exception {
      var taskId = createTask("Mine");
      var url = "/api/v1/organizations/%s/projects/%s/tasks/%s/move"
          .formatted(organizationId, otherProjectId, taskId);

      var body = objectMapper.writeValueAsString(Map.of("status", "DONE"));
      mockMvc.perform(authed(post(url)).contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isNotFound());

      // Confirm at the database level, not just by status code.
      assertThat(taskRepository.findById(taskId).orElseThrow().getStatus())
          .isEqualTo(com.devforge.ai.taskservice.model.TaskStatus.BACKLOG);
    }

    @Test
    @DisplayName("project access is checked on every task request")
    void accessIsCheckedEveryTime() throws Exception {
      var taskId = createTask("Checked");
      Mockito.clearInvocations(projectAccessClient);

      mockMvc.perform(authed(get(tasksUrl() + "/" + taskId))).andExpect(status().isOk());

      // Not cached anywhere: a membership revoked a moment ago must take effect.
      Mockito.verify(projectAccessClient).requireProjectAccess(eq(organizationId), eq(projectId), any());
    }
  }

  // --- Comments, labels, sprints -----------------------------------------

  @Nested
  @DisplayName("Task details")
  class Details {

    @Test
    @DisplayName("comments can be added and listed")
    void commentsRoundTrip() throws Exception {
      var taskId = createTask("Discussed");
      var body = objectMapper.writeValueAsString(Map.of("body", "This needs a second look."));

      mockMvc.perform(authed(post(tasksUrl() + "/" + taskId + "/comments"))
              .contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.data.authorId").value(userId.toString()));

      mockMvc.perform(authed(get(tasksUrl() + "/" + taskId + "/comments")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("only the author can delete a comment")
    void onlyAuthorDeletesComment() throws Exception {
      var taskId = createTask("Guarded");
      var body = objectMapper.writeValueAsString(Map.of("body", "Mine."));
      var created = mockMvc.perform(authed(post(tasksUrl() + "/" + taskId + "/comments"))
              .contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isCreated())
          .andReturn();
      var commentId = dataOf(created).get("id").asText();

      // Seeing a task does not entitle someone to remove another person's words.
      var otherToken = TestTokens.accessToken(UUID.randomUUID());
      mockMvc.perform(delete(tasksUrl() + "/" + taskId + "/comments/" + commentId)
              .header("Authorization", "Bearer " + otherToken))
          .andExpect(status().isForbidden());

      assertThat(commentRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("a label cannot be added twice")
    void duplicateLabelRejected() throws Exception {
      var taskId = createTask("Labelled");

      mockMvc.perform(authed(post(tasksUrl() + "/" + taskId + "/labels").param("label", "backend")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.labels[0]").value("backend"));

      mockMvc.perform(authed(post(tasksUrl() + "/" + taskId + "/labels").param("label", "BACKEND")))
          .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("assigning a task stages a TaskAssigned event")
    void assignmentStagesEvent() throws Exception {
      var taskId = createTask("Assignable");
      outboxRepository.deleteAll();

      var assignee = UUID.randomUUID();
      var body = objectMapper.writeValueAsString(Map.of("assigneeId", assignee.toString()));
      mockMvc.perform(authed(post(tasksUrl() + "/" + taskId + "/assign"))
              .contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.assigneeId").value(assignee.toString()));

      assertThat(outboxRepository.findAll())
          .extracting(e -> e.getEventType())
          .contains("TaskAssigned");
    }

    @Test
    @DisplayName("deleting a task removes its comments and labels")
    void deleteCascades() throws Exception {
      var taskId = createTask("Doomed");
      var comment = objectMapper.writeValueAsString(Map.of("body", "bye"));
      mockMvc.perform(authed(post(tasksUrl() + "/" + taskId + "/comments"))
          .contentType(MediaType.APPLICATION_JSON).content(comment));
      mockMvc.perform(authed(post(tasksUrl() + "/" + taskId + "/labels").param("label", "temp")));

      mockMvc.perform(authed(delete(tasksUrl() + "/" + taskId))).andExpect(status().isOk());

      assertThat(taskRepository.findAll()).isEmpty();
      assertThat(commentRepository.findAll()).isEmpty();
      assertThat(labelRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("a partial update leaves untouched fields alone")
    void partialUpdate() throws Exception {
      var taskId = createTask("Original");

      var body = objectMapper.writeValueAsString(Map.of("priority", "HIGH"));
      mockMvc.perform(authed(patch(tasksUrl() + "/" + taskId))
              .contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.priority").value("HIGH"))
          .andExpect(jsonPath("$.data.title").value("Original"));
    }
  }

  // --- Sprints ------------------------------------------------------------

  @Nested
  @DisplayName("Sprints")
  class Sprints {

    private UUID createSprint(String name) throws Exception {
      var body = objectMapper.writeValueAsString(Map.of("name", name));
      var result = mockMvc.perform(authed(post(sprintsUrl()))
              .contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isCreated())
          .andReturn();
      return UUID.fromString(dataOf(result).get("id").asText());
    }

    @Test
    @DisplayName("a sprint is created planned, not active")
    void createdPlanned() throws Exception {
      var body = objectMapper.writeValueAsString(Map.of("name", "Sprint 1"));
      mockMvc.perform(authed(post(sprintsUrl()))
              .contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.data.status").value("PLANNED"));
    }

    @Test
    @DisplayName("only one sprint can be active at a time")
    void oneActiveSprint() throws Exception {
      var first = createSprint("Sprint 1");
      var second = createSprint("Sprint 2");

      mockMvc.perform(authed(post(sprintsUrl() + "/" + first + "/start")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.status").value("ACTIVE"));

      // Two concurrent sprints make velocity and burndown meaningless.
      mockMvc.perform(authed(post(sprintsUrl() + "/" + second + "/start")))
          .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a sprint that ends before it starts is rejected")
    void invalidDatesRejected() throws Exception {
      var body = objectMapper.writeValueAsString(Map.of(
          "name", "Backwards", "startDate", "2026-06-10", "endDate", "2026-06-01"));
      mockMvc.perform(authed(post(sprintsUrl()))
              .contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("only an active sprint can be completed")
    void completeRequiresActive() throws Exception {
      var sprint = createSprint("Planned only");

      mockMvc.perform(authed(post(sprintsUrl() + "/" + sprint + "/complete")))
          .andExpect(status().isConflict());
    }
  }
}
