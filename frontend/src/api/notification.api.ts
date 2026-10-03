import type { ApiEnvelope } from './auth.api';
import type { Page } from './project.api';

import api from '@/lib/axios';

export type NotificationCategory = 'SECURITY' | 'TASK' | 'PROJECT' | 'SYSTEM';

/**
 * A notification belonging to the signed-in user.
 *
 * There is no `recipientId`: the server never returns one, because the caller is
 * always the recipient. Nothing here is addressable by user id, which is what
 * keeps one person's feed unreachable from another's session.
 */
export interface Notification {
  id: string;
  organizationId: string | null;
  category: NotificationCategory;
  /** The domain event that caused this, e.g. `TaskAssigned`. */
  eventType: string;
  title: string;
  body: string | null;
  /** Relative in-app path, or null when there is nowhere useful to go. */
  link: string | null;
  read: boolean;
  readAt: string | null;
  createdAt: string;
}

export const CATEGORY_LABELS: Record<NotificationCategory, string> = {
  SECURITY: 'Security',
  TASK: 'Task',
  PROJECT: 'Project',
  SYSTEM: 'System',
};

/**
 * None of these take a user id, by design. The recipient comes from the access
 * token, so there is no parameter a client could change to read another feed.
 */
export const notificationApi = {
  list: (unreadOnly = false, page = 0, size = 20) =>
    api.get<ApiEnvelope<Page<Notification>>>(
      `/notifications?unreadOnly=${unreadOnly}&page=${page}&size=${size}`,
    ),

  /** Just the badge count: polled far more often than the feed is opened. */
  unreadCount: () => api.get<ApiEnvelope<{ unread: number }>>('/notifications/unread-count'),

  markRead: (notificationId: string) =>
    api.post<ApiEnvelope<Notification>>(`/notifications/${notificationId}/read`),

  markUnread: (notificationId: string) =>
    api.post<ApiEnvelope<Notification>>(`/notifications/${notificationId}/unread`),

  markAllRead: () => api.post<ApiEnvelope<{ marked: number }>>('/notifications/read-all'),

  remove: (notificationId: string) =>
    api.delete<ApiEnvelope<void>>(`/notifications/${notificationId}`),
};
