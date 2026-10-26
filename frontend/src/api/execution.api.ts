import type { ApiEnvelope } from './auth.api';

import api from '@/lib/axios';

export type ExecutionLanguage = 'PYTHON' | 'JAVASCRIPT';

export type ExecutionStatus =
  'RUNNING' | 'COMPLETED' | 'TIMED_OUT' | 'MEMORY_EXCEEDED' | 'OUTPUT_EXCEEDED' | 'REFUSED';

export interface ExecutionSummary {
  id: string;
  userId: string;
  language: ExecutionLanguage;
  status: ExecutionStatus;
  exitCode: number | null;
  durationMs: number | null;
  createdAt: string;
}

/** stdout and stderr are the program's own text: display them as text, never as markup. */
export interface ExecutionDetail extends ExecutionSummary {
  code: string;
  stdout: string | null;
  stderr: string | null;
  truncated: boolean;
  timeoutSeconds: number;
  memoryMb: number;
  finishedAt: string | null;
}

const base = (o: string, p: string) => `/organizations/${o}/projects/${p}/executions`;

export const executionApi = {
  run: (o: string, p: string, language: ExecutionLanguage, code: string) =>
    api.post<ApiEnvelope<ExecutionDetail>>(base(o, p), { language, code }),

  list: (o: string, p: string) => api.get<ApiEnvelope<ExecutionSummary[]>>(base(o, p)),

  get: (o: string, p: string, id: string) =>
    api.get<ApiEnvelope<ExecutionDetail>>(`${base(o, p)}/${id}`),
};

export const EXECUTION_STATUS_LABELS: Record<ExecutionStatus, string> = {
  RUNNING: 'Running',
  COMPLETED: 'Completed',
  TIMED_OUT: 'Timed out',
  MEMORY_EXCEEDED: 'Out of memory',
  OUTPUT_EXCEEDED: 'Too much output',
  REFUSED: 'Refused',
};
