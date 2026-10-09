import type { ApiEnvelope } from './auth.api';

import api from '@/lib/axios';

export interface ChatMessage {
  id: string;
  authorId: string;
  authorName: string;
  /** Null once deleted: the server erases the text, not just the flag. */
  body: string | null;
  deleted: boolean;
  edited: boolean;
  createdAt: string;
}

export interface DailyMetrics {
  day: string;
  tasksCreated: number;
  tasksCompleted: number;
  tasksAssigned: number;
  commits: number;
  repositoriesCreated: number;
}

export interface ProjectActivity {
  from: string;
  to: string;
  totalTasksCreated: number;
  totalTasksCompleted: number;
  totalTasksAssigned: number;
  totalCommits: number;
  days: DailyMetrics[];
  /** What these numbers can and cannot show — rendered, not hidden. */
  completeness: string;
}

const project = (o: string, p: string) => `/organizations/${o}/projects/${p}`;

export const chatApi = {
  list: (o: string, p: string, after?: string) =>
    api.get<ApiEnvelope<ChatMessage[]>>(`${project(o, p)}/chat/messages`, {
      params: { after, limit: 100 },
    }),
  post: (o: string, p: string, body: string) =>
    api.post<ApiEnvelope<ChatMessage>>(`${project(o, p)}/chat/messages`, { body }),
  remove: (o: string, p: string, id: string) =>
    api.delete<ApiEnvelope<void>>(`${project(o, p)}/chat/messages/${id}`),
};

/** One recorded change. `details` is the event payload as the producing service published it. */
export interface AuditEntry {
  eventId: string;
  eventType: string;
  source: string | null;
  actorId: string | null;
  occurredAt: string;
  details: Record<string, unknown>;
}

export const analyticsApi = {
  activity: (o: string, p: string) =>
    api.get<ApiEnvelope<ProjectActivity>>(`${project(o, p)}/analytics`),

  /** Project admins only; anyone else is answered 403. */
  audit: (o: string, p: string, page = 0) =>
    api.get<
      ApiEnvelope<{
        content: AuditEntry[];
        totalElements: number;
        number: number;
        totalPages: number;
      }>
    >(`${project(o, p)}/analytics/audit`, { params: { page, size: 50 } }),
};
