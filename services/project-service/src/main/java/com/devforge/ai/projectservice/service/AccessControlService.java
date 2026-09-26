package com.devforge.ai.projectservice.service;

import com.devforge.ai.common.exception.ResourceNotFoundException;

import com.devforge.ai.common.security.AuthenticatedUser;
import com.devforge.ai.projectservice.entity.ProjectEntity;
import com.devforge.ai.projectservice.model.OrganizationRole;
import com.devforge.ai.projectservice.model.ProjectRole;
import com.devforge.ai.projectservice.repository.OrganizationMemberRepository;
import com.devforge.ai.projectservice.repository.ProjectMemberRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single place where "may this caller touch this tenant's data?" is decided.
 *
 * <p>Two rules hold everywhere in this service:
 *
 * <ol>
 *   <li><b>Tenant scope is derived, never supplied.</b> The caller's organization comes from a
 *       membership row keyed on their authenticated user id. An organization id in a path or body
 *       is treated as a request, and is only honoured once membership is confirmed.
 *   <li><b>Absence and denial look identical.</b> A resource in another tenant is reported as not
 *       found, not forbidden. Returning 403 would confirm the id exists, which is itself a
 *       cross-tenant information leak.
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class AccessControlService {

  private final OrganizationMemberRepository organizationMemberRepository;
  private final ProjectMemberRepository projectMemberRepository;

  /** The authenticated caller, or empty when the request is anonymous. */
  public Optional<AuthenticatedUser> currentUser() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      return Optional.empty();
    }
    if (authentication.getPrincipal() instanceof AuthenticatedUser user) {
      return Optional.of(user);
    }
    return Optional.empty();
  }

  public AuthenticatedUser requireCurrentUser() {
    return currentUser().orElseThrow(
        () -> new org.springframework.security.access.AccessDeniedException("Not authenticated"));
  }

  @Transactional(readOnly = true)
  public Optional<OrganizationRole> organizationRole(UUID organizationId, UUID userId) {
    return organizationMemberRepository
        .findByOrganizationIdAndUserId(organizationId, userId)
        .map(member -> member.getRole());
  }

  /**
   * Asserts membership of the organization, returning the caller's role.
   *
   * @throws ResourceNotFoundException when the caller is not a member — deliberately the same
   *     signal as an organization that does not exist.
   */
  @Transactional(readOnly = true)
  public OrganizationRole requireOrganizationMember(UUID organizationId, AuthenticatedUser user) {
    return organizationRole(organizationId, user.id())
        .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
  }

  @Transactional(readOnly = true)
  public void requireOrganizationManager(UUID organizationId, AuthenticatedUser user) {
    var role = requireOrganizationMember(organizationId, user);
    if (!role.canManageProjects()) {
      // The caller is a member, so the organization's existence is not a secret from them.
      // A plain denial is correct here.
      throw new org.springframework.security.access.AccessDeniedException(
          "Requires organization OWNER or ADMIN");
    }
  }

  @Transactional(readOnly = true)
  public void requireOrganizationOwner(UUID organizationId, AuthenticatedUser user) {
    var role = requireOrganizationMember(organizationId, user);
    if (!role.canDeleteOrganization()) {
      throw new org.springframework.security.access.AccessDeniedException(
          "Requires organization OWNER");
    }
  }

  /**
   * The caller's effective role on a project.
   *
   * <p>Organization OWNER and ADMIN get administrative access to every project in their own
   * organization without needing an explicit project membership row; otherwise an owner could
   * lock themselves out of a project they are responsible for.
   */
  @Transactional(readOnly = true)
  public Optional<ProjectRole> effectiveProjectRole(ProjectEntity project, AuthenticatedUser user) {
    var organizationId = project.getOrganization().getId();
    var orgRole = organizationRole(organizationId, user.id());
    if (orgRole.isEmpty()) {
      // Not in the tenant at all: no project access, regardless of project membership rows.
      return Optional.empty();
    }
    if (orgRole.get().canManageProjects()) {
      return Optional.of(ProjectRole.ADMIN);
    }
    return projectMemberRepository
        .findByProjectIdAndUserId(project.getId(), user.id())
        .map(member -> member.getRole());
  }

  @Transactional(readOnly = true)
  public ProjectRole requireProjectRead(ProjectEntity project, AuthenticatedUser user) {
    return effectiveProjectRole(project, user)
        .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
  }

  @Transactional(readOnly = true)
  public ProjectRole requireProjectWrite(ProjectEntity project, AuthenticatedUser user) {
    var role = requireProjectRead(project, user);
    if (!role.canWrite()) {
      throw new org.springframework.security.access.AccessDeniedException(
          "Project role " + role + " is read-only");
    }
    return role;
  }

  @Transactional(readOnly = true)
  public ProjectRole requireProjectAdmin(ProjectEntity project, AuthenticatedUser user) {
    var role = requireProjectRead(project, user);
    if (!role.canAdminister()) {
      throw new org.springframework.security.access.AccessDeniedException(
          "Requires project ADMIN or TEAM_LEAD");
    }
    return role;
  }
}
