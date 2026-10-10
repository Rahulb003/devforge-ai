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

export interface RepositoryAnswer {
  question: string;
  answer: string;
  model: string;
  /** The files whose excerpts the answer was drawn from. */
  sources: string[];
  filesSearched: number;
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
    question?: string,
  ) =>
    api.post<ApiEnvelope<Explanation>>(
      `/organizations/${organizationId}/projects/${projectId}/repositories/${repositoryId}/ai/explain`,
      { path, ref, question: question?.trim() || undefined },
    ),

  review: (organizationId: string, projectId: string, repositoryId: string, number: number) =>
    api.post<ApiEnvelope<PullRequestReview>>(
      `/organizations/${organizationId}/projects/${projectId}/repositories/${repositoryId}/ai/pull-requests/${number}/review`,
    ),

  ask: (
    organizationId: string,
    projectId: string,
    repositoryId: string,
    question: string,
    ref: string | undefined,
  ) =>
    api.post<ApiEnvelope<RepositoryAnswer>>(
      `/organizations/${organizationId}/projects/${projectId}/repositories/${repositoryId}/ai/ask`,
      { question: question.trim(), ref },
    ),
};
