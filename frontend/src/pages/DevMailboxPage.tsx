import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ExternalLink, Mail, RefreshCw, Trash2 } from 'lucide-react';
import { Link } from 'react-router-dom';

import { devMailApi } from '@/api/auth.api';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { EmptyState, ErrorState, Skeleton } from '@/components/ui/states';
import { describeApiError } from '@/lib/errors';

/**
 * Development mailbox.
 *
 * Shows what the log mail provider captured, so a verification or reset link is
 * one click away instead of buried in whichever console window owns the service.
 *
 * The backend only serves this when devforge.mail.dev-mailbox-enabled is true,
 * which no real deployment sets, so this page simply reports it is unavailable
 * everywhere else.
 */
export function DevMailboxPage() {
  const queryClient = useQueryClient();

  const messages = useQuery({
    queryKey: ['dev-mailbox'],
    queryFn: async () => (await devMailApi.list()).data.data,
    // Mail arrives from another tab's signup, so poll rather than making the
    // user refresh manually.
    refetchInterval: 4000,
  });

  const clear = useMutation({
    mutationFn: () => devMailApi.clear(),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['dev-mailbox'] }),
  });

  /** Converts an absolute link from the server into an in-app route. */
  function toRoutePath(actionUrl: string): string | null {
    try {
      const url = new URL(actionUrl);
      return `${url.pathname}${url.search}`;
    } catch {
      return null;
    }
  }

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <div className="flex items-center gap-3">
            <h1 className="text-2xl font-semibold text-white">Development mailbox</h1>
            <Badge tone="warning">Not delivered</Badge>
          </div>
          <p className="mt-1 max-w-2xl text-sm text-slate-400">
            The backend is running with{' '}
            <code className="text-slate-300">devforge.mail.provider=log</code>, so these messages
            were captured instead of sent. Open a link here to continue the flow.
          </p>
        </div>
        <div className="flex gap-3">
          <Button
            variant="secondary"
            size="sm"
            leftIcon={<RefreshCw className="h-4 w-4" />}
            onClick={() => messages.refetch()}
          >
            Refresh
          </Button>
          {messages.data && messages.data.length > 0 && (
            <Button
              variant="ghost"
              size="sm"
              leftIcon={<Trash2 className="h-4 w-4" />}
              loading={clear.isPending}
              onClick={() => clear.mutate()}
            >
              Clear
            </Button>
          )}
        </div>
      </header>

      {messages.isLoading && <Skeleton className="h-32" />}

      {messages.isError && (
        <ErrorState
          title="Development mailbox unavailable"
          message={
            'This only exists when the backend runs with the development mail provider. ' +
            describeApiError(messages.error)
          }
          onRetry={() => messages.refetch()}
        />
      )}

      {messages.isSuccess && messages.data.length === 0 && (
        <EmptyState
          icon={<Mail className="h-6 w-6" />}
          title="No captured messages"
          description="Sign up or request a password reset and the message will appear here within a few seconds."
        />
      )}

      {messages.isSuccess && messages.data.length > 0 && (
        <ul className="space-y-3">
          {messages.data.map((message, index) => {
            const routePath = toRoutePath(message.actionUrl);
            return (
              <li key={`${message.sentAt}-${index}`}>
                <Card>
                  <div className="flex flex-wrap items-start justify-between gap-4">
                    <div className="min-w-0">
                      <p className="font-medium text-white">{message.subject}</p>
                      <p className="mt-1 text-sm text-slate-400">
                        To {message.to} · {new Date(message.sentAt).toLocaleString()}
                      </p>
                    </div>
                    {routePath && (
                      <Link to={routePath}>
                        <Button size="sm" leftIcon={<ExternalLink className="h-4 w-4" />}>
                          Open link
                        </Button>
                      </Link>
                    )}
                  </div>
                  <pre className="mt-4 overflow-x-auto whitespace-pre-wrap rounded-xl border border-slate-800 bg-slate-950 px-4 py-3 text-xs text-slate-400">
                    {message.body}
                  </pre>
                </Card>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
