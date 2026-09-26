package com.devforge.ai.projectservice.service;

import com.devforge.ai.common.exception.ResourceNotFoundException;

import com.devforge.ai.common.exception.ResourceConflictException;
import com.devforge.ai.common.security.AuthenticatedUser;
import com.devforge.ai.projectservice.dto.CreateOrganizationRequest;
import com.devforge.ai.projectservice.dto.OrganizationResponse;
import com.devforge.ai.projectservice.entity.OrganizationEntity;
import com.devforge.ai.projectservice.entity.OrganizationMemberEntity;
import com.devforge.ai.projectservice.model.OrganizationRole;
import com.devforge.ai.projectservice.repository.OrganizationMemberRepository;
import com.devforge.ai.projectservice.repository.OrganizationRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrganizationService {

  private final OrganizationRepository organizationRepository;
  private final OrganizationMemberRepository organizationMemberRepository;
  private final AccessControlService accessControl;

  /** Creates an organization and enrols the caller as its OWNER in the same transaction. */
  @Transactional
  public OrganizationResponse create(CreateOrganizationRequest request, AuthenticatedUser user) {
    if (organizationRepository.existsBySlugIgnoreCase(request.slug())) {
      throw new ResourceConflictException("Organization slug is already taken");
    }

    var organization = organizationRepository.save(OrganizationEntity.builder()
        .name(request.name())
        .slug(request.slug().toLowerCase())
        .description(request.description())
        .build());

    // Without this the creator would have no membership row and would immediately lose
    // access to what they just created, since all authorization derives from membership.
    organizationMemberRepository.save(OrganizationMemberEntity.builder()
        .organization(organization)
        .userId(user.id())
        .role(OrganizationRole.OWNER)
        .build());

    log.info("Organization {} created by user {}", organization.getId(), user.id());
    return toResponse(organization, OrganizationRole.OWNER);
  }

  /**
   * Organizations the caller belongs to.
   *
   * <p>Built from the caller's membership rows rather than from all organizations, so the
   * response cannot disclose that other tenants exist.
   */
  @Transactional(readOnly = true)
  public List<OrganizationResponse> listForCurrentUser(AuthenticatedUser user) {
    return organizationMemberRepository.findByUserId(user.id()).stream()
        .map(member -> toResponse(member.getOrganization(), member.getRole()))
        .toList();
  }

  @Transactional(readOnly = true)
  public OrganizationResponse get(UUID organizationId, AuthenticatedUser user) {
    var role = accessControl.requireOrganizationMember(organizationId, user);
    var organization = organizationRepository.findById(organizationId)
        .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
    return toResponse(organization, role);
  }

  @Transactional
  public void delete(UUID organizationId, AuthenticatedUser user) {
    accessControl.requireOrganizationOwner(organizationId, user);
    var organization = organizationRepository.findById(organizationId)
        .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
    organizationRepository.delete(organization);
    log.info("Organization {} deleted by user {}", organizationId, user.id());
  }

  private OrganizationResponse toResponse(OrganizationEntity organization, OrganizationRole role) {
    return new OrganizationResponse(
        organization.getId(),
        organization.getName(),
        organization.getSlug(),
        organization.getDescription(),
        role,
        organization.getCreatedAt());
  }
}
