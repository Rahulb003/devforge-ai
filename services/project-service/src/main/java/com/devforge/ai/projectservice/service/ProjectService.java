package com.devforge.ai.projectservice.service;

import com.devforge.ai.common.events.EventTypes;
import com.devforge.ai.common.exception.ResourceNotFoundException;

import com.devforge.ai.common.exception.ResourceConflictException;
import com.devforge.ai.common.security.AuthenticatedUser;
import com.devforge.ai.projectservice.dto.AddMemberRequest;
import com.devforge.ai.projectservice.dto.CreateProjectRequest;
import com.devforge.ai.projectservice.dto.ProjectMemberResponse;
import com.devforge.ai.projectservice.dto.ProjectResponse;
import com.devforge.ai.projectservice.dto.UpdateProjectRequest;
import com.devforge.ai.projectservice.entity.ProjectEntity;
import com.devforge.ai.projectservice.entity.ProjectMemberEntity;
import com.devforge.ai.projectservice.model.ProjectRole;
import com.devforge.ai.projectservice.model.ProjectStatus;
import com.devforge.ai.projectservice.repository.OrganizationRepository;
import com.devforge.ai.projectservice.repository.ProjectMemberRepository;
import com.devforge.ai.projectservice.repository.ProjectRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Project CRUD and membership.
 *
 * <p>Every method that touches a project resolves it through
 * {@link ProjectRepository#findByIdAndOrganizationId}, so a project id belonging to another
 * organization simply does not resolve. The authorization check that follows is a second layer,
 * not the only one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

  private final ProjectRepository projectRepository;
  private final ProjectMemberRepository projectMemberRepository;
  private final OrganizationRepository organizationRepository;
  private final AccessControlService accessControl;
  private final com.devforge.ai.common.events.outbox.OutboxEventRecorder outbox;

  @Transactional
  public ProjectResponse create(UUID organizationId, CreateProjectRequest request, AuthenticatedUser user) {
    accessControl.requireOrganizationManager(organizationId, user);

    if (projectRepository.existsByOrganizationIdAndProjectKeyIgnoreCase(organizationId, request.projectKey())) {
      throw new ResourceConflictException(
          "Project key '" + request.projectKey() + "' already exists in this organization");
    }

    var organization = organizationRepository.findById(organizationId)
        .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));

    var project = projectRepository.save(ProjectEntity.builder()
        .organization(organization)
        .name(request.name())
        .projectKey(request.projectKey().toUpperCase())
        .description(request.description())
        .status(ProjectStatus.ACTIVE)
        .createdBy(user.id())
        .build());

    projectMemberRepository.save(ProjectMemberEntity.builder()
        .project(project)
        .userId(user.id())
        .role(ProjectRole.ADMIN)
        .build());

    publish(EventTypes.PROJECT_CREATED, organizationId, user, java.util.Map.of(
        "projectId", project.getId().toString(), "name", project.getName(),
        "projectKey", project.getProjectKey()));
    log.info("Project {} created in organization {} by {}", project.getId(), organizationId, user.id());
    return toResponse(project, ProjectRole.ADMIN);
  }

  @Transactional(readOnly = true)
  public Page<ProjectResponse> list(UUID organizationId, AuthenticatedUser user, Pageable pageable) {
    // Membership is asserted before any project row is read, so a non-member gets
    // "organization not found" rather than an empty page that confirms it exists.
    accessControl.requireOrganizationMember(organizationId, user);
    return projectRepository.findByOrganizationId(organizationId, pageable)
        .map(project -> toResponse(
            project,
            accessControl.effectiveProjectRole(project, user).orElse(null)));
  }

  @Transactional(readOnly = true)
  public ProjectResponse get(UUID organizationId, UUID projectId, AuthenticatedUser user) {
    var project = loadScoped(organizationId, projectId);
    var role = accessControl.requireProjectRead(project, user);
    return toResponse(project, role);
  }

  @Transactional
  public ProjectResponse update(
      UUID organizationId, UUID projectId, UpdateProjectRequest request, AuthenticatedUser user) {
    var project = loadScoped(organizationId, projectId);
    var role = accessControl.requireProjectAdmin(project, user);

    // Partial update: only non-null fields are applied.
    if (request.name() != null) {
      project.setName(request.name());
    }
    if (request.description() != null) {
      project.setDescription(request.description());
    }
    publish(EventTypes.PROJECT_UPDATED, organizationId, user, java.util.Map.of(
        "projectId", projectId.toString(), "name", project.getName()));
    return toResponse(projectRepository.save(project), role);
  }

  @Transactional
  public ProjectResponse archive(UUID organizationId, UUID projectId, AuthenticatedUser user) {
    var project = loadScoped(organizationId, projectId);
    var role = accessControl.requireProjectAdmin(project, user);
    project.setStatus(ProjectStatus.ARCHIVED);
    publish(EventTypes.PROJECT_ARCHIVED, organizationId, user, java.util.Map.of(
        "projectId", projectId.toString(), "name", project.getName()));
    return toResponse(projectRepository.save(project), role);
  }

  @Transactional
  public void delete(UUID organizationId, UUID projectId, AuthenticatedUser user) {
    var project = loadScoped(organizationId, projectId);
    accessControl.requireProjectAdmin(project, user);
    // Membership rows reference the project, so they go first.
    projectMemberRepository.deleteAll(projectMemberRepository.findByProjectId(projectId));
    projectRepository.delete(project);
    publish(EventTypes.PROJECT_DELETED, organizationId, user, java.util.Map.of(
        "projectId", projectId.toString(), "name", project.getName()));
    log.info("Project {} deleted by {}", projectId, user.id());
  }

  @Transactional(readOnly = true)
  public List<ProjectMemberResponse> listMembers(UUID organizationId, UUID projectId, AuthenticatedUser user) {
    var project = loadScoped(organizationId, projectId);
    accessControl.requireProjectRead(project, user);
    return projectMemberRepository.findByProjectId(projectId).stream()
        .map(member -> new ProjectMemberResponse(
            member.getId(), member.getUserId(), member.getRole(), member.getCreatedAt()))
        .toList();
  }

  @Transactional
  public ProjectMemberResponse addMember(
      UUID organizationId, UUID projectId, AddMemberRequest request, AuthenticatedUser user) {
    var project = loadScoped(organizationId, projectId);
    accessControl.requireProjectAdmin(project, user);

    // The invitee must already belong to the organization. Without this check, a project
    // admin could pull an arbitrary user id into the tenant and hand them access to data
    // the organization never granted them.
    if (accessControl.organizationRole(organizationId, request.userId()).isEmpty()) {
      throw new ResourceConflictException("User is not a member of this organization");
    }
    if (projectMemberRepository.existsByProjectIdAndUserId(projectId, request.userId())) {
      throw new ResourceConflictException("User is already a member of this project");
    }

    var member = projectMemberRepository.save(ProjectMemberEntity.builder()
        .project(project)
        .userId(request.userId())
        .role(request.role())
        .build());
    publish(EventTypes.PROJECT_MEMBER_ADDED, organizationId, user, java.util.Map.of(
        "projectId", projectId.toString(), "projectName", project.getName(),
        "userId", request.userId().toString(), "role", request.role().name()));
    return new ProjectMemberResponse(
        member.getId(), member.getUserId(), member.getRole(), member.getCreatedAt());
  }

  @Transactional
  public void removeMember(UUID organizationId, UUID projectId, UUID memberUserId, AuthenticatedUser user) {
    var project = loadScoped(organizationId, projectId);
    accessControl.requireProjectAdmin(project, user);
    projectMemberRepository.findByProjectIdAndUserId(projectId, memberUserId)
        .ifPresent(member -> {
          projectMemberRepository.delete(member);
          publish(EventTypes.PROJECT_MEMBER_REMOVED, organizationId, user, java.util.Map.of(
              "projectId", projectId.toString(), "projectName", project.getName(),
              "userId", memberUserId.toString(), "role", member.getRole().name()));
        });
  }

  /**
   * Publishes a change through the outbox, in the same transaction as the change itself, so the
   * event exists exactly when the change does. These feed the audit trail; before them, project
   * and membership changes - including who was given access to what - were recorded nowhere else.
   */
  private void publish(String type, UUID organizationId, AuthenticatedUser user,
      java.util.Map<String, Object> payload) {
    outbox.record(com.devforge.ai.common.events.KafkaTopics.PROJECTS, type, organizationId,
        user.id(), org.slf4j.MDC.get("correlationId"), payload);
  }

  /** Resolves a project within its organization, or reports it as absent. */
  private ProjectEntity loadScoped(UUID organizationId, UUID projectId) {
    return projectRepository.findByIdAndOrganizationId(projectId, organizationId)
        .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
  }

  private ProjectResponse toResponse(ProjectEntity project, ProjectRole role) {
    return new ProjectResponse(
        project.getId(),
        project.getOrganization().getId(),
        project.getName(),
        project.getProjectKey(),
        project.getDescription(),
        project.getStatus(),
        role,
        project.getCreatedBy(),
        project.getCreatedAt(),
        project.getUpdatedAt());
  }
}
