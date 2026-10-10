package com.devforge.ai.projectservice.controller;

import com.devforge.ai.common.web.ApiResponse;
import com.devforge.ai.projectservice.model.OrganizationRole;
import com.devforge.ai.projectservice.service.AccessControlService;
import com.devforge.ai.projectservice.service.MembershipService;
import com.devforge.ai.projectservice.service.MembershipService.Invitation;
import com.devforge.ai.projectservice.service.MembershipService.Member;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Organization members and invitations.
 *
 * <p>Two sides: an organization's managers send and revoke invitations and manage members under
 * {@code /organizations/{id}}; the invited person sees and answers their own invitations under
 * {@code /invitations}, which is always about the caller.
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MembershipController {

  private final MembershipService membership;
  private final AccessControlService accessControl;

  public record InviteRequest(String email, OrganizationRole role) {}

  public record RoleRequest(OrganizationRole role) {}

  @GetMapping("/api/v1/organizations/{organizationId}/members")
  public ResponseEntity<ApiResponse<List<Member>>> members(@PathVariable UUID organizationId) {
    return ok(membership.members(organizationId, accessControl.requireCurrentUser()));
  }

  @PatchMapping("/api/v1/organizations/{organizationId}/members/{userId}")
  public ResponseEntity<ApiResponse<Member>> changeRole(
      @PathVariable UUID organizationId, @PathVariable UUID userId, @RequestBody RoleRequest request) {
    return ok(membership.changeRole(organizationId, userId, request.role(), accessControl.requireCurrentUser()));
  }

  /** Removing someone, or - with your own id - leaving. */
  @DeleteMapping("/api/v1/organizations/{organizationId}/members/{userId}")
  public ResponseEntity<ApiResponse<Void>> remove(@PathVariable UUID organizationId, @PathVariable UUID userId) {
    membership.remove(organizationId, userId, accessControl.requireCurrentUser());
    return ok(null);
  }

  /**
   * Leaves every organization at once. auth-service calls this, with the caller's own token, before
   * it deletes their account; 409 while they are the only owner of any organization.
   */
  @DeleteMapping("/api/v1/memberships/mine")
  public ResponseEntity<ApiResponse<java.util.Map<String, Integer>>> leaveAll() {
    return ok(java.util.Map.of("organizationsLeft", membership.leaveAll(accessControl.requireCurrentUser())));
  }

  @PostMapping("/api/v1/organizations/{organizationId}/invitations")
  public ResponseEntity<ApiResponse<Invitation>> invite(
      @PathVariable UUID organizationId, @RequestBody InviteRequest request) {
    var invitation = membership.invite(
        organizationId, request.email(), request.role(), accessControl.requireCurrentUser());
    return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(true, invitation, "Invitation sent"));
  }

  @GetMapping("/api/v1/organizations/{organizationId}/invitations")
  public ResponseEntity<ApiResponse<List<Invitation>>> pending(@PathVariable UUID organizationId) {
    return ok(membership.pendingInvitations(organizationId, accessControl.requireCurrentUser()));
  }

  @DeleteMapping("/api/v1/organizations/{organizationId}/invitations/{invitationId}")
  public ResponseEntity<ApiResponse<Void>> revoke(
      @PathVariable UUID organizationId, @PathVariable UUID invitationId) {
    membership.revoke(organizationId, invitationId, accessControl.requireCurrentUser());
    return ok(null);
  }

  @GetMapping("/api/v1/invitations")
  public ResponseEntity<ApiResponse<List<Invitation>>> mine() {
    return ok(membership.mine(accessControl.requireCurrentUser()));
  }

  @PostMapping("/api/v1/invitations/{invitationId}/accept")
  public ResponseEntity<ApiResponse<Member>> accept(@PathVariable UUID invitationId) {
    return ok(membership.accept(invitationId, accessControl.requireCurrentUser()));
  }

  @PostMapping("/api/v1/invitations/{invitationId}/decline")
  public ResponseEntity<ApiResponse<Void>> decline(@PathVariable UUID invitationId) {
    membership.decline(invitationId, accessControl.requireCurrentUser());
    return ok(null);
  }

  private static <T> ResponseEntity<ApiResponse<T>> ok(T data) {
    return ResponseEntity.ok(new ApiResponse<>(true, data, null));
  }
}
