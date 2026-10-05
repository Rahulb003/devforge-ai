import type { ApiEnvelope } from './auth.api';
import type { Page } from './project.api';

import api from '@/lib/axios';

export type ReviewStatus = 'RUNNING' | 'COMPLETED' | 'FAILED';
export type GateResult = 'PASS' | 'FAIL';
export type Severity = 'BLOCKER' | 'HIGH' | 'MEDIUM' | 'LOW' | 'INFO';
export type FindingCategory =
  'SECRET' | 'CREDENTIAL_FILE' | 'MERGE_CONFLICT' | 'DANGEROUS_PATTERN' | 'MAINTAINABILITY';

export interface Review {
  id: string;
  repositoryId: string;
  ref: string;
  /** Set when only the difference between two refs was analysed. */
  baseRef: string | null;
  status: ReviewStatus;
  /** Null while running, and when the review failed — a failed review never claims PASS. */
  gate: GateResult | null;
  filesAnalysed: number;
  blockerCount: number;
  highCount: number;
  mediumCount: number;
  lowCount: number;
  /** Why analysis could not complete, e.g. the repository could not be read. */
  failureReason: string | null;
  requestedBy: string;
  createdAt: string;
  completedAt: string | null;
}

export interface Finding {
  id: string;
  ruleId: string;
  severity: Severity;
  category: FindingCategory;
  filePath: string;
  /** Null when the finding concerns the file as a whole. */
  lineNumber: number | null;
  message: string;
  /**
   * The offending line, already redacted by the server where it held a credential.
   * It will never contain the secret itself.
   */
  snippet: string | null;
  dismissed: boolean;
  dismissedAt: string | null;
  dismissedBy: string | null;
  dismissReason: string | null;
}

export const SEVERITY_LABELS: Record<Severity, string> = {
  BLOCKER: 'Blocker',
  HIGH: 'High',
  MEDIUM: 'Medium',
  LOW: 'Low',
  INFO: 'Info',
};

export const CATEGORY_LABELS: Record<FindingCategory, string> = {
  SECRET: 'Credential in code',
  CREDENTIAL_FILE: 'Credential file',
  MERGE_CONFLICT: 'Merge conflict',
  DANGEROUS_PATTERN: 'Risky pattern',
  MAINTAINABILITY: 'Maintainability',
};

function base(organizationId: string, projectId: string, repositoryId: string) {
  return `/organizations/${organizationId}/projects/${projectId}/repositories/${repositoryId}/reviews`;
}

export const reviewApi = {
  /**
   * Runs a review and returns the finished result.
   *
   * Synchronous on the server — analysis is bounded — so there is no job to poll.
   * It can take a few seconds on a large repository.
   */
  run: (
    organizationId: string,
    projectId: string,
    repositoryId: string,
    body: { ref?: string; baseRef?: string } = {},
  ) => api.post<ApiEnvelope<Review>>(base(organizationId, projectId, repositoryId), body),

  list: (organizationId: string, projectId: string, repositoryId: string, page = 0, size = 20) =>
    api.get<ApiEnvelope<Page<Review>>>(
      `${base(organizationId, projectId, repositoryId)}?page=${page}&size=${size}`,
    ),

  /** The newest review, for a status badge. 404 when the repository has never been reviewed. */
  latest: (organizationId: string, projectId: string, repositoryId: string) =>
    api.get<ApiEnvelope<Review>>(`${base(organizationId, projectId, repositoryId)}/latest`),

  get: (organizationId: string, projectId: string, repositoryId: string, reviewId: string) =>
    api.get<ApiEnvelope<Review>>(`${base(organizationId, projectId, repositoryId)}/${reviewId}`),

  findings: (organizationId: string, projectId: string, repositoryId: string, reviewId: string) =>
    api.get<ApiEnvelope<Finding[]>>(
      `${base(organizationId, projectId, repositoryId)}/${reviewId}/findings`,
    ),

  /** A reason is required by the server: a dismissal without one is unreviewable later. */
  dismiss: (
    organizationId: string,
    projectId: string,
    repositoryId: string,
    reviewId: string,
    findingId: string,
    reason: string,
  ) =>
    api.post<ApiEnvelope<Finding>>(
      `${base(organizationId, projectId, repositoryId)}/${reviewId}/findings/${findingId}/dismiss`,
      { reason },
    ),
};
