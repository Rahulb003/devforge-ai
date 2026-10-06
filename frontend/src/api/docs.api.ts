import type { ApiEnvelope } from './auth.api';
import type { Page } from './project.api';

import api from '@/lib/axios';

export type DocSetStatus = 'RUNNING' | 'COMPLETED' | 'FAILED';
export type DocumentKind = 'OVERVIEW' | 'API_SURFACE' | 'DOC_COVERAGE';

export interface DocSet {
  id: string;
  repositoryId: string;
  ref: string;
  status: DocSetStatus;
  filesScanned: number;
  /** Why generation could not finish. A failed set must never read as "nothing to document". */
  failureReason: string | null;
  generatedBy: string;
  createdAt: string;
  completedAt: string | null;
}

export interface GeneratedDocument {
  id: string;
  kind: DocumentKind;
  title: string;
  /** Markdown. Null in a listing — fetched per document, since a set is a lot of text. */
  content: string | null;
  createdAt: string;
}

export const DOCUMENT_KIND_LABELS: Record<DocumentKind, string> = {
  OVERVIEW: 'Overview',
  API_SURFACE: 'API surface',
  DOC_COVERAGE: 'Doc coverage',
};

function base(organizationId: string, projectId: string, repositoryId: string) {
  return `/organizations/${organizationId}/projects/${projectId}/repositories/${repositoryId}/docs`;
}

export const docsApi = {
  generate: (organizationId: string, projectId: string, repositoryId: string, ref?: string) =>
    api.post<ApiEnvelope<DocSet>>(base(organizationId, projectId, repositoryId), { ref }),

  list: (organizationId: string, projectId: string, repositoryId: string) =>
    api.get<ApiEnvelope<Page<DocSet>>>(base(organizationId, projectId, repositoryId)),

  /** The newest set. 404 when nothing has been generated yet. */
  latest: (organizationId: string, projectId: string, repositoryId: string) =>
    api.get<ApiEnvelope<DocSet>>(`${base(organizationId, projectId, repositoryId)}/latest`),

  documents: (organizationId: string, projectId: string, repositoryId: string, docSetId: string) =>
    api.get<ApiEnvelope<GeneratedDocument[]>>(
      `${base(organizationId, projectId, repositoryId)}/${docSetId}/documents`,
    ),

  document: (
    organizationId: string,
    projectId: string,
    repositoryId: string,
    docSetId: string,
    kind: DocumentKind,
  ) =>
    api.get<ApiEnvelope<GeneratedDocument>>(
      `${base(organizationId, projectId, repositoryId)}/${docSetId}/documents/${kind}`,
    ),
};
