import { useQuery } from '@tanstack/react-query';
import { ArrowRight, Sunrise } from 'lucide-react';
import { Link } from 'react-router-dom';

import type { NotificationCategory } from '@/api/notification.api';
import { CATEGORY_LABELS, notificationApi } from '@/api/notification.api';
import { Card } from '@/components/ui/Card';
import { EmptyState, ErrorState, Skeleton } from '@/components/ui/states';
import { describeApiError } from '@/lib/errors';
import { relativeTime } from '@/lib/time';

/** Told apart by lightness as well as hue, so the dots still differ without colour vision. */
const DOT: Record<NotificationCategory, string> = {
  SECURITY: 'bg-red-400 shadow-[0_0_12px] shadow-red-400/70',
  TASK: 'bg-indigo-400 shadow-[0_0_12px] shadow-indigo-400/70',
  PROJECT: 'bg-teal-300 shadow-[0_0_12px] shadow-teal-300/70',
  CODE: 'bg-forge-400 shadow-[0_0_12px] shadow-forge-400/70',
  SYSTEM: 'bg-slate-500',
};

/**
 * What needs the viewer today: their latest notifications - task assignments, security alerts,
 * project and code events - newest first, unread ones emphasised.
 *
 * Built only from the notification feed that already exists. A richer "your day" (failed
 * pipelines, reviews waiting) would need a query across services that nothing answers yet, and a
 * panel that guessed would be the kind of invented dashboard this project does not ship.
 */
export function YourDay() {
  const feed = useQuery({
    queryKey: ['notifications', 'your-day'],
    queryFn: async () => (await notificationApi.list(false, 0, 6)).data.data.content,
  });

  return (
    <Card as="section" aria-labelledby="your-day">
      <div className="mb-4 flex items-center justify-between gap-4">
        <h2 id="your-day" className="font-display text-lg font-semibold text-white">
          Your day
        </h2>
        <Link
          to="/notifications"
          className="inline-flex items-center gap-1 text-sm text-indigo-300 underline-offset-4 hover:underline"
        >
          All notifications <ArrowRight className="h-4 w-4" aria-hidden="true" />
        </Link>
      </div>

      {feed.isLoading ? (
        <div className="space-y-3">
          <Skeleton className="h-16" />
          <Skeleton className="h-16" />
          <Skeleton className="h-16" />
        </div>
      ) : feed.isError ? (
        <ErrorState message={describeApiError(feed.error)} onRetry={() => feed.refetch()} />
      ) : !feed.data?.length ? (
        <EmptyState
          icon={<Sunrise className="h-6 w-6" />}
          title="Nothing needs you right now"
          description="Task assignments, security alerts and project events land here as they happen."
        />
      ) : (
        <ul className="space-y-2.5">
          {feed.data.map((n) => {
            const inner = (
              <>
                <span
                  className={`h-2.5 w-2.5 shrink-0 rounded-full ${DOT[n.category]}`}
                  aria-hidden="true"
                />
                <span className="min-w-0 flex-1">
                  <span
                    className={`block truncate text-[15px] ${
                      n.read ? 'text-slate-300' : 'font-semibold text-white'
                    }`}
                  >
                    {n.title}
                  </span>
                  <span className="mt-0.5 block truncate text-sm text-slate-400">
                    {CATEGORY_LABELS[n.category]}
                    {n.body ? ` · ${n.body}` : ''}
                  </span>
                </span>
                <span className="shrink-0 font-mono text-xs text-slate-400">
                  {relativeTime(n.createdAt)}
                </span>
                {!n.read && <span className="sr-only">(unread)</span>}
              </>
            );
            const row =
              'flex items-center gap-3.5 rounded-2xl border border-slate-800/80 bg-slate-950/40 px-4 py-3';
            return (
              <li key={n.id}>
                {n.link ? (
                  <Link
                    to={n.link}
                    className={`${row} transition hover:border-slate-700 hover:bg-slate-900/60`}
                  >
                    {inner}
                  </Link>
                ) : (
                  <div className={row}>{inner}</div>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </Card>
  );
}
