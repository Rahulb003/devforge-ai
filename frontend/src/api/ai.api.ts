import type { ApiEnvelope } from './auth.api';

import api from '@/lib/axios';

export interface AiStatus {
  configured: boolean;
  model: string;
}

export interface Explanation {
  path: string;
  ref: string;
  /** Plain text from the model; shown as text, never as HTML. */
  explanation: string;
  model: string;
  /** Only the beginning of the file was sent. */
  truncated: boolean;
}

export interface PullRequestReview {
  number: number;
  review: string;
  model: string;
  filesReviewed: number;
  filesChanged: number;
  truncated: boolean;
}

export const aiApi = {
  status: () => api.get<ApiEnvelope<AiStatus>>('/ai/status'),

  explain: (
    organizationId: string,
    projectId: string,
    repositoryId: string,
    path: string,
    ref: string | undefined,
  ) =>
    api.post<ApiEnvelope<Explanation>>(
      `/organizations/${organizationId}/projects/${projectId}/repositories/${repositoryId}/ai/explain`,
      { path, ref },
    ),

  review: (organizationId: string, projectId: string, repositoryId: string, number: number) =>
    api.post<ApiEnvelope<PullRequestReview>>(
      `/organizations/${organizationId}/projects/${projectId}/repositories/${repositoryId}/ai/pull-requests/${number}/review`,
    ),
};
