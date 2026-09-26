import type { ApiEnvelope } from './auth.api';

import api from '@/lib/axios';

export type OrganizationRole = 'OWNER' | 'ADMIN' | 'MEMBER';
export type ProjectRole = 'ADMIN' | 'TEAM_LEAD' | 'DEVELOPER' | 'TESTER' | 'VIEWER';
export type ProjectStatus = 'ACTIVE' | 'ARCHIVED';

export interface Organization {
  id: string;
  name: string;
  slug: string;
  description: string | null;
  /** The requesting user's role. Never another member's. */
  role: OrganizationRole;
  createdAt: string;
}

export interface Project {
  id: string;
  organizationId: string;
  name: string;
  projectKey: string;
  description: string | null;
  status: ProjectStatus;
  /** The caller's effective role, or null if they have none. */
  role: ProjectRole | null;
  createdBy: string;
  createdAt: string;
  updatedAt: string | null;
}

export interface ProjectMember {
  id: string;
  userId: string;
  role: ProjectRole;
  createdAt: string;
}

/** Spring Data's page shape, as the API returns it. */
export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export interface CreateOrganizationData {
  name: string;
  slug: string;
  description?: string;
}

export interface CreateProjectData {
  name: string;
  projectKey: string;
  description?: string;
}

/**
 * Projects are addressed under their organization.
 *
 * The nesting is not cosmetic: the server resolves a project only within the
 * organization in the path, so a project id from another tenant cannot resolve.
 */
export const projectApi = {
  listOrganizations: () => api.get<ApiEnvelope<Organization[]>>('/organizations'),

  getOrganization: (organizationId: string) =>
    api.get<ApiEnvelope<Organization>>(`/organizations/${organizationId}`),

  createOrganization: (data: CreateOrganizationData) =>
    api.post<ApiEnvelope<Organization>>('/organizations', data),

  deleteOrganization: (organizationId: string) =>
    api.delete<ApiEnvelope<void>>(`/organizations/${organizationId}`),

  listProjects: (organizationId: string, page = 0, size = 20) =>
    api.get<ApiEnvelope<Page<Project>>>(
      `/organizations/${organizationId}/projects?page=${page}&size=${size}`,
    ),

  getProject: (organizationId: string, projectId: string) =>
    api.get<ApiEnvelope<Project>>(`/organizations/${organizationId}/projects/${projectId}`),

  createProject: (organizationId: string, data: CreateProjectData) =>
    api.post<ApiEnvelope<Project>>(`/organizations/${organizationId}/projects`, data),

  updateProject: (organizationId: string, projectId: string, data: Partial<CreateProjectData>) =>
    api.patch<ApiEnvelope<Project>>(`/organizations/${organizationId}/projects/${projectId}`, data),

  archiveProject: (organizationId: string, projectId: string) =>
    api.post<ApiEnvelope<Project>>(
      `/organizations/${organizationId}/projects/${projectId}/archive`,
    ),

  deleteProject: (organizationId: string, projectId: string) =>
    api.delete<ApiEnvelope<void>>(`/organizations/${organizationId}/projects/${projectId}`),

  listMembers: (organizationId: string, projectId: string) =>
    api.get<ApiEnvelope<ProjectMember[]>>(
      `/organizations/${organizationId}/projects/${projectId}/members`,
    ),
};
