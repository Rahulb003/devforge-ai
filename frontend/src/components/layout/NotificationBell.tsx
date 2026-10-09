import { useQuery } from '@tanstack/react-query';
import { Bell } from 'lucide-react';
import { Link } from 'react-router-dom';

import { notificationApi } from '@/api/notification.api';

/**
 * The unread badge in the header.
 *
 * Polls rather than holding a socket open. A WebSocket would deliver faster, but
 * it is a connection per signed-in tab to maintain and reconnect, and a count
 * that is up to a minute stale costs the user nothing. When chat (§11) brings a
 * real-time channel, this should move onto it rather than keeping its own.
 *
 * A failed request renders the bell with no badge instead of an error: the count
 * is peripheral, and a broken notification service must not put an error banner
 * across every page in the app.
 */
export function NotificationBell() {
  const unread = useQuery({
    queryKey: ['notifications', 'unread-count'],
    queryFn: async () => (await notificationApi.unreadCount()).data.data.unread,
    refetchInterval: 60_000,
    // Checked again when the user comes back to the tab, which is when a stale
    // count is most obvious to them.
    refetchOnWindowFocus: true,
    retry: false,
  });

  const count = unread.data ?? 0;
  const label = count > 0 ? `Notifications (${count} unread)` : 'Notifications';

  return (
    <Link
      to="/notifications"
      aria-label={label}
      title={label}
      className="relative inline-flex h-11 w-11 items-center justify-center rounded-xl border border-slate-700 bg-slate-800 text-slate-300 transition hover:border-slate-600 hover:text-white focus-visible:outline-solid focus-visible:outline-2 focus-visible:outline-indigo-400"
    >
      <Bell className="h-5 w-5" aria-hidden="true" />
      {count > 0 && (
        <span
          // aria-hidden because the count is already in the link's accessible
          // name; announcing it twice is worse than not at all.
          aria-hidden="true"
          className="absolute -right-1 -top-1 grid min-w-5 place-items-center rounded-full bg-indigo-500 px-1 text-xs font-semibold text-white"
        >
          {count > 99 ? '99+' : count}
        </span>
      )}
    </Link>
  );
}
