import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Bell, Check, CheckCheck, ShieldAlert, SquareKanban, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';

import type { Notification, NotificationCategory } from '@/api/notification.api';
import { notificationApi } from '@/api/notification.api';
import { Button } from '@/components/ui/Button';
import { EmptyState, ErrorState, LoadingState } from '@/components/ui/states';
import { describeApiError } from '@/lib/errors';

const CATEGORY_ICON: Record<NotificationCategory, typeof Bell> = {
  SECURITY: ShieldAlert,
  TASK: SquareKanban,
  PROJECT: SquareKanban,
  SYSTEM: Bell,
};

/** Security alerts are visually distinct, because they are the ones worth interrupting for. */
const CATEGORY_STYLE: Record<NotificationCategory, string> = {
  SECURITY: 'bg-amber-500/10 text-amber-300',
  TASK: 'bg-indigo-500/10 text-indigo-300',
  PROJECT: 'bg-sky-500/10 text-sky-300',
  SYSTEM: 'bg-slate-700/40 text-slate-300',
};

function relativeTime(iso: string): string {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return '';
  const seconds = Math.round((Date.now() - then) / 1000);
  if (seconds < 60) return 'just now';
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.round(hours / 24);
  if (days < 30) return `${days}d ago`;
  return new Date(iso).toLocaleDateString();
}

export function NotificationsPage() {
  const [unreadOnly, setUnreadOnly] = useState(false);
  const queryClient = useQueryClient();

  const notifications = useQuery({
    queryKey: ['notifications', { unreadOnly }],
    queryFn: async () => (await notificationApi.list(unreadOnly)).data.data,
  });

  /**
   * Both the list and the badge are refreshed after any change.
   *
   * The count is a separate query from the list, so updating one without the
   * other leaves the bell contradicting the page the user is looking at.
   */
  function invalidateAll() {
    void queryClient.invalidateQueries({ queryKey: ['notifications'] });
    void queryClient.invalidateQueries({ queryKey: ['notifications', 'unread-count'] });
  }

  const markRead = useMutation({
    mutationFn: (id: string) => notificationApi.markRead(id),
    onSuccess: invalidateAll,
  });

  const markUnread = useMutation({
    mutationFn: (id: string) => notificationApi.markUnread(id),
    onSuccess: invalidateAll,
  });

  const markAllRead = useMutation({
    mutationFn: () => notificationApi.markAllRead(),
    onSuccess: invalidateAll,
  });

  const remove = useMutation({
    mutationFn: (id: string) => notificationApi.remove(id),
    onSuccess: invalidateAll,
  });

  const items = notifications.data?.content ?? [];
  const hasUnread = items.some((item) => !item.read);

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-white">Notifications</h1>
          <p className="mt-1 text-sm text-slate-400">
            Raised by events from across the platform — assignments, and changes to your account.
          </p>
        </div>

        <div className="flex items-center gap-2">
          <Button
            type="button"
            variant="secondary"
            onClick={() => setUnreadOnly((value) => !value)}
            aria-pressed={unreadOnly}
          >
            {unreadOnly ? 'Show all' : 'Unread only'}
          </Button>
          <Button
            type="button"
            onClick={() => markAllRead.mutate()}
            disabled={!hasUnread}
            loading={markAllRead.isPending}
            leftIcon={<CheckCheck className="h-4 w-4" aria-hidden="true" />}
          >
            Mark all read
          </Button>
        </div>
      </header>

      {markAllRead.isError && (
        <p
          className="rounded-xl border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-300"
          role="alert"
        >
          {describeApiError(markAllRead.error)}
        </p>
      )}

      {notifications.isLoading && <LoadingState label="Loading notifications…" />}

      {notifications.isError && (
        <ErrorState
          message={describeApiError(notifications.error)}
          onRetry={() => void notifications.refetch()}
        />
      )}

      {notifications.isSuccess && items.length === 0 && (
        <EmptyState
          icon={<Bell className="h-6 w-6" aria-hidden="true" />}
          title={unreadOnly ? 'Nothing unread' : 'No notifications yet'}
          description={
            unreadOnly
              ? 'Everything here has been read.'
              : 'When someone assigns you a task, or something changes on your account, it will appear here.'
          }
        />
      )}

      {items.length > 0 && (
        <ul className="space-y-3">
          {items.map((notification) => (
            <NotificationRow
              key={notification.id}
              notification={notification}
              onMarkRead={() => markRead.mutate(notification.id)}
              onMarkUnread={() => markUnread.mutate(notification.id)}
              onRemove={() => remove.mutate(notification.id)}
            />
          ))}
        </ul>
      )}
    </div>
  );
}

function NotificationRow({
  notification,
  onMarkRead,
  onMarkUnread,
  onRemove,
}: {
  notification: Notification;
  onMarkRead: () => void;
  onMarkUnread: () => void;
  onRemove: () => void;
}) {
  const Icon = CATEGORY_ICON[notification.category] ?? Bell;

  return (
    <li
      className={`flex gap-4 rounded-2xl border p-4 transition ${
        notification.read
          ? 'border-slate-800 bg-slate-900/60'
          : 'border-slate-700 bg-slate-900 shadow-lg'
      }`}
    >
      <div
        className={`grid h-10 w-10 shrink-0 place-items-center rounded-xl ${
          CATEGORY_STYLE[notification.category] ?? CATEGORY_STYLE.SYSTEM
        }`}
      >
        <Icon className="h-5 w-5" aria-hidden="true" />
      </div>

      <div className="min-w-0 flex-1">
        <div className="flex items-start justify-between gap-3">
          <p
            className={`text-sm font-semibold ${notification.read ? 'text-slate-300' : 'text-white'}`}
          >
            {notification.title}
            {!notification.read && (
              <span className="ml-2 inline-block h-2 w-2 rounded-full bg-indigo-400 align-middle">
                {/* Announced in words too: colour alone is not a status. */}
                <span className="sr-only">Unread</span>
              </span>
            )}
          </p>
          <time className="shrink-0 text-xs text-slate-500" dateTime={notification.createdAt}>
            {relativeTime(notification.createdAt)}
          </time>
        </div>

        {notification.body && (
          <p className="mt-1 break-words text-sm text-slate-400">{notification.body}</p>
        )}

        <div className="mt-3 flex flex-wrap items-center gap-2">
          {notification.link && (
            // Stored as a relative path, so it cannot send the user off-origin.
            <Link
              to={notification.link}
              onClick={() => {
                if (!notification.read) onMarkRead();
              }}
              className="text-sm font-medium text-indigo-400 underline-offset-4 hover:underline"
            >
              Open
            </Link>
          )}

          <button
            type="button"
            onClick={notification.read ? onMarkUnread : onMarkRead}
            className="inline-flex items-center gap-1.5 rounded-lg px-2 py-1 text-xs font-medium text-slate-400 transition hover:bg-slate-800 hover:text-white focus-visible:outline focus-visible:outline-2 focus-visible:outline-indigo-400"
          >
            <Check className="h-3.5 w-3.5" aria-hidden="true" />
            {notification.read ? 'Mark unread' : 'Mark read'}
          </button>

          <button
            type="button"
            onClick={onRemove}
            aria-label={`Delete notification: ${notification.title}`}
            className="inline-flex items-center gap-1.5 rounded-lg px-2 py-1 text-xs font-medium text-slate-500 transition hover:bg-slate-800 hover:text-red-300 focus-visible:outline focus-visible:outline-2 focus-visible:outline-indigo-400"
          >
            <Trash2 className="h-3.5 w-3.5" aria-hidden="true" />
            Delete
          </button>
        </div>
      </div>
    </li>
  );
}
