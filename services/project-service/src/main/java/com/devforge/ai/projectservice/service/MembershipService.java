package com.devforge.ai.projectservice.service;

import com.devforge.ai.common.events.EventTypes;
import com.devforge.ai.common.events.KafkaTopics;
import com.devforge.ai.common.events.outbox.OutboxEventRecorder;
import com.devforge.ai.common.exception.ResourceConflictException;
import com.devforge.ai.common.exception.ResourceNotFoundException;
import com.devforge.ai.common.security.AuthenticatedUser;
import com.devforge.ai.projectservice.entity.OrganizationInvitationEntity;
import com.devforge.ai.projectservice.entity.OrganizationMemberEntity;
import com.devforge.ai.projectservice.model.OrganizationRole;
import com.devforge.ai.projectservice.repository.OrganizationInvitationRepository;
import com.devforge.ai.projectservice.repository.OrganizationMemberRepository;
import com.devforge.ai.projectservice.repository.OrganizationRepository;
import com.devforge.ai.projectservice.repository.ProjectMemberRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who belongs to an organization, and how people join it.
 *
 * <p>People join by invitation to an email address, accepted by an account whose token carries
 * that address as verified. Inviting answers the same whether or not an account exists for the
 * address, so it cannot be used to find out who is registered. Every decision here comes from
 * membership rows; nothing is taken from the request beyond what is being asked for.
 */
@Service
@RequiredArgsConstructor
public class MembershipService {

  static final Duration INVITATION_LIFETIME = Duration.ofDays(14);
  static final int MAX_PENDING_INVITATIONS = 100;
  private static final Pattern EMAIL = Pattern.compile("[^@\\s]{1,64}@[^@\\s]{1,190}\\.[^@\\s.]{2,}");

  private final OrganizationRepository organizations;
  private final OrganizationMemberRepository members;
  private final OrganizationInvitationRepository invitations;
  private final ProjectMemberRepository projectMembers;
  private final AccessControlService accessControl;
  private final OutboxEventRecorder outbox;

  public record Member(UUID userId, String username, String email, OrganizationRole role, Instant joinedAt) {}

  public record Invitation(
      UUID id, UUID organizationId, String organizationName, String email, OrganizationRole role,
      Instant createdAt, Instant expiresAt) {}

  // ------------------------------------------------------------------ members

  @Transactional(readOnly = true)
  public List<Member> members(UUID organizationId, AuthenticatedUser user) {
    accessControl.requireOrganizationMember(organizationId, user);
    return members.findByOrganizationId(organizationId).stream()
        .map(m -> new Member(m.getUserId(), m.getUsername(), m.getEmail(), m.getRole(), m.getCreatedAt()))
        .toList();
  }

  @Transactional
  public Member changeRole(UUID organizationId, UUID memberId, OrganizationRole newRole, AuthenticatedUser user) {
    var callerRole = requireManager(organizationId, user);
    var member = members.findByOrganizationIdAndUserId(organizationId, memberId)
        .orElseThrow(() -> new ResourceNotFoundException("Member not found"));
    if (newRole == null) {
      throw new IllegalArgumentException("A role is required");
    }
    // Owners are made and unmade by owners: an admin could otherwise promote themselves past the
    // person who granted them admin, or demote them.
    if ((newRole == OrganizationRole.OWNER || member.getRole() == OrganizationRole.OWNER)
        && callerRole != OrganizationRole.OWNER) {
      throw new AccessDeniedException("Only an owner can grant or change the owner role");
    }
    if (member.getRole() == OrganizationRole.OWNER && newRole != OrganizationRole.OWNER) {
      requireAnotherOwner(organizationId);
    }
    var previous = member.getRole();
    member.setRole(newRole);
    publish(EventTypes.ORGANIZATION_MEMBER_ROLE_CHANGED, organizationId, user, Map.of(
        "userId", memberId.toString(), "role", newRole.name(), "previousRole", previous.name(),
        "organizationName", member.getOrganization().getName()));
    return new Member(member.getUserId(), member.getUsername(), member.getEmail(), newRole, member.getCreatedAt());
  }

  /** Removes a member, or lets a member leave. Their project memberships in it go with them. */
  @Transactional
  public void remove(UUID organizationId, UUID memberId, AuthenticatedUser user) {
    var callerRole = accessControl.requireOrganizationMember(organizationId, user);
    var leaving = memberId.equals(user.id());
    if (!leaving && !callerRole.canManageMembers()) {
      throw new AccessDeniedException("Requires organization OWNER or ADMIN");
    }
    var member = members.findByOrganizationIdAndUserId(organizationId, memberId)
        .orElseThrow(() -> new ResourceNotFoundException("Member not found"));
    if (member.getRole() == OrganizationRole.OWNER) {
      if (!leaving && callerRole != OrganizationRole.OWNER) {
        throw new AccessDeniedException("Only an owner can remove an owner");
      }
      requireAnotherOwner(organizationId);
    }
    // Otherwise they would keep project access: project roles are checked against project rows.
    projectMembers.deleteAll(projectMembers.findByProjectOrganizationIdAndUserId(organizationId, memberId));
    members.delete(member);
    publish(EventTypes.ORGANIZATION_MEMBER_REMOVED, organizationId, user, Map.of(
        "userId", memberId.toString(), "role", member.getRole().name(),
        "organizationName", member.getOrganization().getName()));
  }

  // -------------------------------------------------------------- invitations

  @Transactional
  public Invitation invite(UUID organizationId, String email, OrganizationRole role, AuthenticatedUser user) {
    requireManager(organizationId, user);
    var address = normalisedEmail(email);
    var granted = role == null ? OrganizationRole.MEMBER : role;
    if (granted == OrganizationRole.OWNER) {
      // Ownership is granted to someone already in the organization, not to an address.
      throw new IllegalArgumentException("Invite as MEMBER or ADMIN; make someone an owner once they have joined");
    }
    var organization = organizations.findById(organizationId)
        .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
    if (members.findByOrganizationId(organizationId).stream()
        .anyMatch(m -> address.equalsIgnoreCase(m.getEmail()))) {
      throw new ResourceConflictException("That address already belongs to a member");
    }
    var now = Instant.now();
    var pending = invitations.findByOrganizationIdOrderByCreatedAtDesc(organizationId).stream()
        .filter(i -> i.isPendingAt(now)).toList();
    if (pending.stream().anyMatch(i -> i.getEmail().equals(address))) {
      throw new ResourceConflictException("An invitation to that address is already pending");
    }
    if (pending.size() >= MAX_PENDING_INVITATIONS) {
      throw new IllegalArgumentException("Too many pending invitations; revoke some first");
    }
    var invitation = invitations.save(OrganizationInvitationEntity.builder()
        .organization(organization)
        .email(address)
        .role(granted)
        .invitedBy(user.id())
        .expiresAt(now.plus(INVITATION_LIFETIME))
        .build());
    // No email address in the event: events carry ids and names, and an address is neither.
    publish(EventTypes.ORGANIZATION_INVITATION_SENT, organizationId, user, Map.of(
        "invitationId", invitation.getId().toString(), "role", granted.name(),
        "organizationName", organization.getName()));
    return view(invitation);
  }

  @Transactional(readOnly = true)
  public List<Invitation> pendingInvitations(UUID organizationId, AuthenticatedUser user) {
    requireManager(organizationId, user);
    var now = Instant.now();
    return invitations.findByOrganizationIdOrderByCreatedAtDesc(organizationId).stream()
        .filter(i -> i.isPendingAt(now))
        .map(MembershipService::view)
        .toList();
  }

  @Transactional
  public void revoke(UUID organizationId, UUID invitationId, AuthenticatedUser user) {
    requireManager(organizationId, user);
    var invitation = invitations.findByIdAndOrganizationId(invitationId, organizationId)
        .filter(i -> i.isPendingAt(Instant.now()))
        .orElseThrow(() -> new ResourceNotFoundException("Invitation not found"));
    invitation.setRevokedAt(Instant.now());
  }

  /** Invitations waiting for the caller. None until their email address is verified. */
  @Transactional(readOnly = true)
  public List<Invitation> mine(AuthenticatedUser user) {
    if (!user.emailVerified() || user.email() == null) {
      return List.of();
    }
    var now = Instant.now();
    return invitations.findByEmailOrderByCreatedAtDesc(user.email().toLowerCase(Locale.ROOT)).stream()
        .filter(i -> i.isPendingAt(now))
        .map(MembershipService::view)
        .toList();
  }

  @Transactional
  public Member accept(UUID invitationId, AuthenticatedUser user) {
    var invitation = addressedTo(invitationId, user);
    var organizationId = invitation.getOrganization().getId();
    invitation.setAcceptedAt(Instant.now());
    // Already a member some other way: accepting changes nothing, and does not lower their role.
    var existing = members.findByOrganizationIdAndUserId(organizationId, user.id());
    if (existing.isPresent()) {
      var m = existing.get();
      return new Member(m.getUserId(), m.getUsername(), m.getEmail(), m.getRole(), m.getCreatedAt());
    }
    var member = members.save(OrganizationMemberEntity.builder()
        .organization(invitation.getOrganization())
        .userId(user.id())
        .role(invitation.getRole())
        .username(user.username())
        .email(user.email())
        .build());
    publish(EventTypes.ORGANIZATION_MEMBER_ADDED, organizationId, user, Map.of(
        "userId", user.id().toString(), "role", invitation.getRole().name(),
        "organizationName", invitation.getOrganization().getName()));
    return new Member(member.getUserId(), member.getUsername(), member.getEmail(), member.getRole(), member.getCreatedAt());
  }

  @Transactional
  public void decline(UUID invitationId, AuthenticatedUser user) {
    addressedTo(invitationId, user).setDeclinedAt(Instant.now());
  }

  // ------------------------------------------------------------------ helpers

  /**
   * The pending invitation, if it is addressed to the caller's verified email. Anything else is
   * "not found", so an invitation id seen elsewhere reveals nothing and cannot be used.
   */
  private OrganizationInvitationEntity addressedTo(UUID invitationId, AuthenticatedUser user) {
    return invitations.findById(invitationId)
        .filter(i -> i.isPendingAt(Instant.now()))
        .filter(i -> user.emailVerified() && user.email() != null
            && i.getEmail().equals(user.email().toLowerCase(Locale.ROOT)))
        .orElseThrow(() -> new ResourceNotFoundException("Invitation not found"));
  }

  private OrganizationRole requireManager(UUID organizationId, AuthenticatedUser user) {
    var role = accessControl.requireOrganizationMember(organizationId, user);
    if (!role.canManageMembers()) {
      throw new AccessDeniedException("Requires organization OWNER or ADMIN");
    }
    return role;
  }

  /** An organization with no owner could never be deleted or have its owners changed again. */
  private void requireAnotherOwner(UUID organizationId) {
    if (members.countByOrganizationIdAndRole(organizationId, OrganizationRole.OWNER) < 2) {
      throw new ResourceConflictException("An organization needs at least one owner; make someone else an owner first");
    }
  }

  private static String normalisedEmail(String email) {
    if (email == null || email.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("A valid email address is required");
    }
    var address = email.trim().toLowerCase(Locale.ROOT);
    if (address.length() > 255 || !EMAIL.matcher(address).matches()) {
      throw new IllegalArgumentException("A valid email address is required");
    }
    return address;
  }

  private static Invitation view(OrganizationInvitationEntity i) {
    return new Invitation(i.getId(), i.getOrganization().getId(), i.getOrganization().getName(),
        i.getEmail(), i.getRole(), i.getCreatedAt(), i.getExpiresAt());
  }

  private void publish(String type, UUID organizationId, AuthenticatedUser user, Map<String, Object> payload) {
    outbox.record(KafkaTopics.PROJECTS, type, organizationId, user.id(), MDC.get("correlationId"), payload);
  }
}
