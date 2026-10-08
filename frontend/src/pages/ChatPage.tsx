import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, MessageSquare } from 'lucide-react';
import { useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router-dom';

import { chatApi } from '@/api/chat.api';
import { Button } from '@/components/ui/Button';
import { EmptyState, ErrorState, LoadingState } from '@/components/ui/states';
import { useChatStream } from '@/lib/chatStream';
import { describeApiError } from '@/lib/errors';
import { useAuthStore } from '@/stores/authStore';

/**
 * A project's channel.
 *
 * New messages arrive over a live stream. A slow poll stays on as a fallback, so a dropped stream
 * degrades to slightly late rather than silently stale.
 */
export function ChatPage() {
  const { organizationId = '', projectId = '' } = useParams();
  const queryClient = useQueryClient();
  const me = useAuthStore((s) => s.user);
  const [draft, setDraft] = useState('');
  const [error, setError] = useState<string | null>(null);

  const messages = useQuery({
    queryKey: ['chat', projectId],
    // Newest first from the server; reversed so the conversation reads top to bottom.
    queryFn: async () => [...(await chatApi.list(organizationId, projectId)).data.data].reverse(),
    enabled: Boolean(organizationId && projectId),
    // Fallback only: the live stream triggers a refetch the moment something is posted.
    refetchInterval: 30_000,
  });

  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['chat', projectId] });

  const streamStatus = useChatStream(organizationId, projectId, refresh);

  const send = useMutation({
    mutationFn: () => chatApi.post(organizationId, projectId, draft.trim()),
    onSuccess: () => {
      setDraft('');
      setError(null);
      refresh();
    },
    onError: (err) => setError(describeApiError(err)),
  });

  const remove = useMutation({
    mutationFn: (id: string) => chatApi.remove(organizationId, projectId, id),
    onSuccess: refresh,
    onError: (err) => setError(describeApiError(err)),
  });

  const items = messages.data ?? [];

  return (
    <div className="space-y-6">
      <Link
        to={`/organizations/${organizationId}/projects/${projectId}`}
        className="inline-flex items-center gap-2 text-sm text-slate-400 underline-offset-4 hover:text-slate-200 hover:underline"
      >
        <ArrowLeft className="h-4 w-4" aria-hidden="true" />
        Back to board
      </Link>

      <header>
        <h1 className="text-2xl font-semibold text-white">Project chat</h1>
        <p className="mt-1 text-sm text-slate-400" role="status">
          {streamStatus === 'live'
            ? 'Live — new messages appear as they are sent.'
            : 'Reconnecting… messages may arrive a little late.'}
        </p>
      </header>

      {error && (
        <div
          role="alert"
          className="rounded-xl border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-300"
        >
          {error}
        </div>
      )}

      {messages.isLoading && <LoadingState label="Loading messages…" />}
      {messages.isError && (
        <ErrorState
          message={describeApiError(messages.error)}
          onRetry={() => void messages.refetch()}
        />
      )}
      {messages.isSuccess && items.length === 0 && (
        <EmptyState
          icon={<MessageSquare className="h-6 w-6" aria-hidden="true" />}
          title="No messages yet"
          description="Start the conversation for this project."
        />
      )}

      {items.length > 0 && (
        <ul className="space-y-3" aria-label="Messages">
          {items.map((m) => (
            <li key={m.id} className="rounded-2xl border border-slate-800 bg-slate-900 px-4 py-3">
              <div className="flex items-baseline justify-between gap-3">
                <p className="text-sm font-semibold text-slate-200">{m.authorName}</p>
                <time className="text-xs text-slate-500" dateTime={m.createdAt}>
                  {new Date(m.createdAt).toLocaleTimeString()}
                  {m.edited && ' · edited'}
                </time>
              </div>
              {m.deleted ? (
                <p className="mt-1 text-sm italic text-slate-500">Message deleted</p>
              ) : (
                // Plain text: React escapes it, so a message cannot inject markup.
                <p className="mt-1 whitespace-pre-wrap break-words text-sm text-slate-300">
                  {m.body}
                </p>
              )}
              {!m.deleted && me?.id === m.authorId && (
                <button
                  type="button"
                  onClick={() => remove.mutate(m.id)}
                  className="mt-2 text-xs text-slate-500 hover:text-red-300"
                  aria-label={`Delete your message: ${m.body ?? ''}`}
                >
                  Delete
                </button>
              )}
            </li>
          ))}
        </ul>
      )}

      <form
        onSubmit={(e: FormEvent) => {
          e.preventDefault();
          if (draft.trim()) send.mutate();
        }}
        className="flex gap-3"
      >
        <label htmlFor="chat-input" className="sr-only">
          Message
        </label>
        <input
          id="chat-input"
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          maxLength={4000}
          placeholder="Write a message"
          className="flex-1 rounded-xl border border-slate-700 bg-slate-800 px-4 py-2 text-sm text-slate-100 focus-visible:outline focus-visible:outline-2 focus-visible:outline-indigo-400"
        />
        <Button type="submit" loading={send.isPending} disabled={!draft.trim()}>
          Send
        </Button>
      </form>
    </div>
  );
}
