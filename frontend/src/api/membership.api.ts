import type { ApiEnvelope } from './auth.api';
import type { OrganizationRole } from './project.api';

import api from '@/lib/axios';

/** A member as the organization knows them: name and address from when they joined. */
export interface OrganizationMember {
  userId: string;
  username: string | null;
  email: string | null;
  role: OrganizationRole;
  joinedAt: string;
}

export interface OrganizationInvitation {
  id: string;
  organizationId: string;
  organizationName: string;
  email: string;
  role: OrganizationRole;
  createdAt: string;
  expiresAt: string;
}

const org = (organizationId: string) => `/organizations/${organizationId}`;

export const membershipApi = {
  members: (organizationId: string) =>
    api.get<ApiEnvelope<OrganizationMember[]>>(`${org(organizationId)}/members`),

  changeRole: (organizationId: string, userId: string, role: OrganizationRole) =>
    api.patch<ApiEnvelope<OrganizationMember>>(`${org(organizationId)}/members/${userId}`, {
      role,
    }),

  /** With the caller's own id, this is leaving. */
  remove: (organizationId: string, userId: string) =>
    api.delete<ApiEnvelope<void>>(`${org(organizationId)}/members/${userId}`),

  invite: (organizationId: string, email: string, role: OrganizationRole) =>
    api.post<ApiEnvelope<OrganizationInvitation>>(`${org(organizationId)}/invitations`, {
      email,
      role,
    }),

  pending: (organizationId: string) =>
    api.get<ApiEnvelope<OrganizationInvitation[]>>(`${org(organizationId)}/invitations`),

  revoke: (organizationId: string, invitationId: string) =>
    api.delete<ApiEnvelope<void>>(`${org(organizationId)}/invitations/${invitationId}`),

  /** Invitations waiting for the signed-in user; empty until their email is verified. */
  mine: () => api.get<ApiEnvelope<OrganizationInvitation[]>>('/invitations'),

  accept: (invitationId: string) =>
    api.post<ApiEnvelope<OrganizationMember>>(`/invitations/${invitationId}/accept`),

  decline: (invitationId: string) =>
    api.post<ApiEnvelope<void>>(`/invitations/${invitationId}/decline`),
};
