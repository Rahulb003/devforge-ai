import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { KeyRound, Trash2 } from 'lucide-react';
import { useState, type FormEvent } from 'react';

import { authApi, type CreatedAccessToken } from '@/api/auth.api';
import { Button } from '@/components/ui/Button';
import { Card, CardHeader } from '@/components/ui/Card';
import { Input } from '@/components/ui/Input';
import { EmptyState, ErrorState, Skeleton } from '@/components/ui/states';
import { describeApiError } from '@/lib/errors';

function formatDate(value: string | null): string {
  if (!value) return 'never';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? 'unknown' : date.toLocaleDateString();
}

const LIFETIMES = [
  { days: 30, label: '30 days' },
  { days: 90, label: '90 days' },
  { days: 365, label: '1 year' },
];

/**
 * Personal access tokens, for git over HTTP.
 *
 * A token is shown once, straight after it is created; the server keeps only a hash, so there is
 * nothing to show later. It works for git and nothing else, which the copy says, so nobody pastes
 * one somewhere expecting it to call the API.
 */
export function AccessTokensSection() {
  const queryClient = useQueryClient();
  const [name, setName] = useState('');
  const [days, setDays] = useState(90);
  const [created, setCreated] = useState<CreatedAccessToken | null>(null);
  const [error, setError] = useState<string | null>(null);

  const tokens = useQuery({
    queryKey: ['access-tokens'],
    queryFn: async () => (await authApi.listAccessTokens()).data.data,
  });

  const create = useMutation({
    mutationFn: async () => (await authApi.createAccessToken(name, days)).data.data,
    onSuccess: (token) => {
      setError(null);
      setName('');
      setCreated(token);
      void queryClient.invalidateQueries({ queryKey: ['access-tokens'] });
    },
    onError: (err) => setError(describeApiError(err)),
  });

  const revoke = useMutation({
    mutationFn: (tokenId: string) => authApi.revokeAccessToken(tokenId),
    onSuccess: (_, tokenId) => {
      setError(null);
      if (created?.details.id === tokenId) setCreated(null);
      void queryClient.invalidateQueries({ queryKey: ['access-tokens'] });
    },
    onError: (err) => setError(describeApiError(err)),
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    if (name.trim()) create.mutate();
  }

  return (
    <Card>
      <CardHeader
        title="Access tokens"
        description="For cloning and pushing with git over HTTPS: use a token as the password. A token works for git only, not for the rest of DevForge."
      />

      {error && (
        <div
          role="alert"
          className="mb-4 rounded-xl border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-300"
        >
          {error}
        </div>
      )}

      <form
        onSubmit={submit}
        aria-label="New access token"
        className="mb-5 flex flex-wrap items-end gap-3"
      >
        <div className="min-w-48 flex-1">
          <Input
            label="Token name"
            placeholder="e.g. work laptop"
            maxLength={100}
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </div>
        <label className="text-sm text-slate-300">
          <span className="mb-1.5 block font-medium">Expires after</span>
          <select
            value={days}
            onChange={(e) => setDays(Number(e.target.value))}
            className="h-11 rounded-xl border border-slate-700 bg-slate-950 px-3 text-slate-100"
          >
            {LIFETIMES.map((lifetime) => (
              <option key={lifetime.days} value={lifetime.days}>
                {lifetime.label}
              </option>
            ))}
          </select>
        </label>
        <Button type="submit" loading={create.isPending} disabled={!name.trim()}>
          Create token
        </Button>
      </form>

      {created && (
        <div
          role="status"
          className="mb-5 space-y-2 rounded-xl border border-emerald-500/40 bg-emerald-500/10 px-4 py-3"
        >
          <p className="text-sm text-emerald-200">
            Copy “{created.details.name}” now. It will not be shown again.
          </p>
          <div className="flex flex-wrap items-center gap-2">
            <code
              aria-label="New token"
              className="rounded-lg bg-slate-950 px-3 py-2 text-xs break-all text-slate-100"
            >
              {created.token}
            </code>
            <Button
              variant="secondary"
              size="sm"
              onClick={() => void navigator.clipboard?.writeText(created.token)}
            >
              Copy
            </Button>
            <Button variant="ghost" size="sm" onClick={() => setCreated(null)}>
              Done
            </Button>
          </div>
        </div>
      )}

      {tokens.isLoading && <Skeleton className="h-16" />}

      {tokens.isError && (
        <ErrorState message={describeApiError(tokens.error)} onRetry={() => tokens.refetch()} />
      )}

      {tokens.isSuccess && tokens.data.length === 0 && (
        <EmptyState
          icon={<KeyRound className="h-6 w-6" />}
          title="No access tokens"
          description="Create one to clone and push repositories with git."
        />
      )}

      {tokens.isSuccess && tokens.data.length > 0 && (
        <ul className="space-y-3" aria-label="Access tokens">
          {tokens.data.map((token) => {
            const expired = new Date(token.expiresAt).getTime() <= Date.now();
            return (
              <li
                key={token.id}
                className="flex flex-wrap items-center justify-between gap-4 rounded-xl border border-slate-800 bg-slate-950 px-4 py-3"
              >
                <div className="min-w-0">
                  <p className="truncate font-medium text-slate-200">{token.name}</p>
                  <p className="mt-1 text-xs text-slate-400">
                    <code>{token.prefix}…</code> · created {formatDate(token.createdAt)} ·{' '}
                    {expired ? 'expired' : 'expires'} {formatDate(token.expiresAt)} · last used{' '}
                    {formatDate(token.lastUsedAt)}
                  </p>
                </div>
                <Button
                  variant="ghost"
                  size="sm"
                  leftIcon={<Trash2 className="h-4 w-4" />}
                  loading={revoke.isPending && revoke.variables === token.id}
                  onClick={() => revoke.mutate(token.id)}
                  aria-label={`Revoke ${token.name}`}
                >
                  Revoke
                </Button>
              </li>
            );
          })}
        </ul>
      )}
    </Card>
  );
}
