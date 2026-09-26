import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { LogOut, Monitor } from 'lucide-react';
import { useState } from 'react';

import { authApi, type SessionSummary } from '@/api/auth.api';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { Card, CardHeader } from '@/components/ui/Card';
import { EmptyState, ErrorState, Skeleton } from '@/components/ui/states';
import { describeApiError } from '@/lib/errors';

function formatWhen(value: string | null): string {
  if (!value) return 'unknown';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return 'unknown';
  return date.toLocaleString();
}

/**
 * Active sessions, one row per device.
 *
 * The list exists because signing in somewhere no longer displaces an existing
 * session: each device holds its own refresh token, so a user needs to be able
 * to see and end them individually.
 */
export function SessionsSection() {
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);

  const sessions = useQuery({
    queryKey: ['sessions'],
    queryFn: async () => (await authApi.listSessions()).data.data,
  });

  const revoke = useMutation({
    mutationFn: (sessionId: string) => authApi.revokeSession(sessionId),
    onSuccess: () => {
      setError(null);
      queryClient.invalidateQueries({ queryKey: ['sessions'] });
    },
    onError: (err) => setError(describeApiError(err)),
  });

  const revokeOthers = useMutation({
    mutationFn: () => authApi.revokeOtherSessions(),
    onSuccess: () => {
      setError(null);
      queryClient.invalidateQueries({ queryKey: ['sessions'] });
    },
    onError: (err) => setError(describeApiError(err)),
  });

  const others = (sessions.data ?? []).filter((s: SessionSummary) => !s.current).length;

  return (
    <Card>
      <CardHeader
        title="Active sessions"
        description="Every device you are signed in on. Ending a session signs that device out immediately."
        action={
          others > 0 ? (
            <Button
              variant="secondary"
              size="sm"
              loading={revokeOthers.isPending}
              onClick={() => revokeOthers.mutate()}
            >
              Sign out {others} other{others === 1 ? '' : 's'}
            </Button>
          ) : undefined
        }
      />

      {error && (
        <div
          role="alert"
          className="mb-4 rounded-xl border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-300"
        >
          {error}
        </div>
      )}

      {sessions.isLoading && (
        <div className="space-y-3">
          {[0, 1].map((i) => (
            <Skeleton key={i} className="h-20" />
          ))}
        </div>
      )}

      {sessions.isError && (
        <ErrorState message={describeApiError(sessions.error)} onRetry={() => sessions.refetch()} />
      )}

      {sessions.isSuccess && sessions.data.length === 0 && (
        <EmptyState
          icon={<Monitor className="h-6 w-6" />}
          title="No active sessions"
          description="Sessions appear here once you sign in on a device."
        />
      )}

      {sessions.isSuccess && sessions.data.length > 0 && (
        <ul className="space-y-3">
          {sessions.data.map((session) => (
            <li
              key={session.id}
              className="flex flex-wrap items-center justify-between gap-4 rounded-xl border border-slate-800 bg-slate-950 px-4 py-3"
            >
              <div className="min-w-0">
                <div className="flex items-center gap-2">
                  <Monitor className="h-4 w-4 shrink-0 text-slate-500" aria-hidden="true" />
                  <p className="truncate font-medium text-slate-200">
                    {session.deviceLabel ?? 'Unknown device'}
                  </p>
                  {session.current && <Badge tone="info">This device</Badge>}
                </div>
                <p className="mt-1 text-xs text-slate-500">
                  {session.ipAddress ?? 'unknown IP'} · signed in {formatWhen(session.createdAt)} ·
                  last used {formatWhen(session.lastUsedAt)}
                </p>
              </div>

              {/* The current session is ended by signing out, not from this
                  list: revoking it here would log the user out mid-task with
                  no explanation. */}
              {!session.current && (
                <Button
                  variant="ghost"
                  size="sm"
                  leftIcon={<LogOut className="h-4 w-4" />}
                  loading={revoke.isPending && revoke.variables === session.id}
                  onClick={() => revoke.mutate(session.id)}
                >
                  Sign out
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}
