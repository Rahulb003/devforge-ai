import type { ApiEnvelope } from './auth.api';
import type { Page } from './project.api';

import api from '@/lib/axios';

/**
 * Self-hosted git repositories.
 *
 * These are repositories DevForge hosts itself, served by JGit on the backend —
 * not a GitHub or GitLab integration, which does not exist yet.
 */
export interface Repository {
  id: string;
  projectId: string;
  organizationId: string;
  name: string;
  description: string | null;
  defaultBranch: string;
  /** True until the first commit. Browsing an empty repository is not an error. */
  empty: boolean;
  createdBy: string;
  createdAt: string;
  updatedAt: string | null;
}

export interface Branch {
  name: string;
  commitId: string;
  isDefault: boolean;
}

export interface Commit {
  id: string;
  shortId: string;
  message: string;
  authorName: string | null;
  authorEmail: string | null;
  committedAt: string;
  parentIds: string[];
}

export interface TreeEntry {
  name: string;
  path: string;
  type: 'FILE' | 'DIRECTORY';
  /** Null for a directory: computing it means walking the whole subtree. */
  size: number | null;
}

export interface Blob {
  path: string;
  size: number;
  /** When true, `content` is null rather than mangled text. */
  binary: boolean;
  /** True when the file exceeded the server's read limit. */
  truncated: boolean;
  content: string | null;
}

export interface DiffEntry {
  changeType: 'ADD' | 'MODIFY' | 'DELETE' | 'RENAME' | 'COPY';
  oldPath: string | null;
  newPath: string | null;
  linesAdded: number;
  linesDeleted: number;
}

export interface Diff {
  from: string;
  to: string;
  entries: DiffEntry[];
}

export interface CreateRepositoryData {
  name: string;
  description?: string;
  initialBranch?: string;
}

export interface CommitFileData {
  path: string;
  content: string;
  message: string;
  branch?: string;
}

/** One file in a multi-file commit: new content, or `delete`. */
export type FileChange = { path: string; content: string } | { path: string; delete: true };

export interface CommitChangesData {
  message: string;
  branch?: string;
  /** The commit the edits started from; the server answers 409 if the branch has moved since. */
  baseCommitId: string;
  changes: FileChange[];
}

/**
 * Refs and paths go in the query string, matching the API.
 *
 * A file path contains slashes, so putting it in the URL path would need encoding
 * that gets normalised before the server sees it — which is exactly where
 * traversal checks become hard to reason about.
 */
function base(organizationId: string, projectId: string) {
  return `/organizations/${organizationId}/projects/${projectId}/repositories`;
}

export const gitApi = {
  list: (organizationId: string, projectId: string, page = 0, size = 20) =>
    api.get<ApiEnvelope<Page<Repository>>>(
      `${base(organizationId, projectId)}?page=${page}&size=${size}`,
    ),

  get: (organizationId: string, projectId: string, repositoryId: string) =>
    api.get<ApiEnvelope<Repository>>(`${base(organizationId, projectId)}/${repositoryId}`),

  create: (organizationId: string, projectId: string, data: CreateRepositoryData) =>
    api.post<ApiEnvelope<Repository>>(base(organizationId, projectId), data),

  remove: (organizationId: string, projectId: string, repositoryId: string) =>
    api.delete<ApiEnvelope<void>>(`${base(organizationId, projectId)}/${repositoryId}`),

  branches: (organizationId: string, projectId: string, repositoryId: string) =>
    api.get<ApiEnvelope<Branch[]>>(`${base(organizationId, projectId)}/${repositoryId}/branches`),

  createBranch: (
    organizationId: string,
    projectId: string,
    repositoryId: string,
    name: string,
    fromRef?: string,
  ) =>
    api.post<ApiEnvelope<Branch>>(`${base(organizationId, projectId)}/${repositoryId}/branches`, {
      name,
      fromRef,
    }),

  commits: (
    organizationId: string,
    projectId: string,
    repositoryId: string,
    ref?: string,
    page = 0,
    size = 30,
  ) =>
    api.get<ApiEnvelope<Commit[]>>(`${base(organizationId, projectId)}/${repositoryId}/commits`, {
      params: { ref, page, size },
    }),

  tree: (
    organizationId: string,
    projectId: string,
    repositoryId: string,
    ref?: string,
    path?: string,
  ) =>
    api.get<ApiEnvelope<TreeEntry[]>>(`${base(organizationId, projectId)}/${repositoryId}/tree`, {
      params: { ref, path },
    }),

  blob: (
    organizationId: string,
    projectId: string,
    repositoryId: string,
    path: string,
    ref?: string,
  ) =>
    api.get<ApiEnvelope<Blob>>(`${base(organizationId, projectId)}/${repositoryId}/blob`, {
      params: { ref, path },
    }),

  diff: (
    organizationId: string,
    projectId: string,
    repositoryId: string,
    from: string,
    to: string,
  ) =>
    api.get<ApiEnvelope<Diff>>(`${base(organizationId, projectId)}/${repositoryId}/diff`, {
      params: { from, to },
    }),

  commitFile: (
    organizationId: string,
    projectId: string,
    repositoryId: string,
    data: CommitFileData,
  ) =>
    api.post<ApiEnvelope<Commit>>(`${base(organizationId, projectId)}/${repositoryId}/files`, data),

  commitChanges: (
    organizationId: string,
    projectId: string,
    repositoryId: string,
    data: CommitChangesData,
  ) =>
    api.post<ApiEnvelope<Commit>>(
      `${base(organizationId, projectId)}/${repositoryId}/commits`,
      data,
    ),
};
