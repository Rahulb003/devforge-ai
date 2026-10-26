import type { ApiEnvelope } from './auth.api';

import api from '@/lib/axios';

export type PipelineRunStatus =
  'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'ERROR' | 'INTERRUPTED';

export type PipelineStepStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'TIMED_OUT'
  | 'MEMORY_EXCEEDED'
  | 'OUTPUT_EXCEEDED'
  | 'SKIPPED'
  | 'REFUSED';

export interface PipelineRunSummary {
  id: string;
  ref: string;
  status: PipelineRunStatus;
  triggeredBy: string;
  createdAt: string;
  finishedAt: string | null;
}

/** stdout and stderr are the program's own text: display them as text, never as markup. */
export interface PipelineStep {
  position: number;
  name: string;
  runtime: string;
  script: string;
  timeoutSeconds: number;
  status: PipelineStepStatus;
  exitCode: number | null;
  stdout: string | null;
  stderr: string | null;
  truncated: boolean;
  durationMs: number | null;
}

export interface PipelineRun extends PipelineRunSummary {
  repositoryId: string;
  message: string | null;
  omittedFiles: string[];
  startedAt: string | null;
  steps: PipelineStep[];
}

const base = (o: string, p: string, r: string) =>
  `/organizations/${o}/projects/${p}/repositories/${r}/pipelines`;

export const pipelineApi = {
  trigger: (o: string, p: string, r: string, ref?: string) =>
    api.post<ApiEnvelope<PipelineRun>>(base(o, p, r), { ref: ref || undefined }),

  list: (o: string, p: string, r: string) =>
    api.get<ApiEnvelope<PipelineRunSummary[]>>(base(o, p, r)),

  get: (o: string, p: string, r: string, id: string) =>
    api.get<ApiEnvelope<PipelineRun>>(`${base(o, p, r)}/${id}`),
};

export const isFinished = (status: PipelineRunStatus) =>
  status !== 'QUEUED' && status !== 'RUNNING';
