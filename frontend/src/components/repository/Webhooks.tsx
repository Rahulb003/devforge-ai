import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AxiosError } from 'axios';
import { Trash2, Webhook as WebhookIcon } from 'lucide-react';
import { useState, type FormEvent } from 'react';

import { webhookApi } from '@/api/git.api';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { Input } from '@/components/ui/Input';
import { describeApiError } from '@/lib/errors';

/**
 * A repository's webhooks, for project admins.
 *
 * Closed until asked for, so the list is only fetched by someone looking for it; anyone who is not
 * an admin then sees the server's refusal, not a form that would fail. The signing secret is shown
 * once, straight from the create response - the server cannot show it again.
 */
export function Webhooks({
  organizationId,
  projectId,
  repositoryId,
}: {
  organizationId: string;
  projectId: string;
  repositoryId: string;
}) {
  const [open, setOpen] = useState(false);
  const [url, setUrl] = useState('');
  const [secret, setSecret] = useState<string | null>(null);
  const queryClient = useQueryClient();
  const key = ['webhooks', repositoryId];

  const list = useQuery({
    queryKey: key,
    queryFn: async () => (await webhookApi.list(organizationId, projectId, repositoryId)).data.data,
    enabled: open,
    retry: false,
  });
  const create = useMutation({
    mutationFn: async () =>
      (await webhookApi.create(organizationId, projectId, repositoryId, url.trim())).data.data,
    onSuccess: (created) => {
      setSecret(created.secret);
      setUrl('');
      void queryClient.invalidateQueries({ queryKey: key });
    },
  });
  const remove = useMutation({
    mutationFn: (webhookId: string) =>
      webhookApi.remove(organizationId, projectId, repositoryId, webhookId),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: key }),
  });

  if (!open) {
    return (
      <div>
        <Button
          variant="ghost"
          size="sm"
          leftIcon={<WebhookIcon className="h-4 w-4" />}
          onClick={() => setOpen(true)}
        >
          Webhooks
        </Button>
      </div>
    );
  }

  const forbidden = list.error instanceof AxiosError && list.error.response?.status === 403;

  function submit(event: FormEvent) {
    event.preventDefault();
    if (url.trim()) create.mutate();
  }

  return (
    <Card>
      <section aria-label="Webhooks" className="space-y-4 text-sm">
        <div className="flex items-center justify-between gap-3">
          <h2 className="text-base font-semibold text-white">Webhooks</h2>
          <Button variant="ghost" size="sm" onClick={() => setOpen(false)}>
            Close
          </Button>
        </div>
        <p className="text-slate-400">
          A signed <code>POST</code> to each URL whenever a branch changes: a commit, a merge or a
          push. Verify <code>X-DevForge-Signature</code> - the HMAC-SHA256 of the body with the
          secret.
        </p>

        {list.isLoading && <p className="text-slate-400">Loading webhooks...</p>}
        {forbidden && <p className="text-slate-400">Only project admins manage webhooks.</p>}
        {list.isError && !forbidden && (
          <p role="alert" className="text-red-300">
            {describeApiError(list.error)}
          </p>
        )}

        {list.isSuccess && (
          <>
            {list.data.length === 0 ? (
              <p className="text-slate-400">No webhooks yet.</p>
            ) : (
              <ul className="divide-y divide-slate-800">
                {list.data.map((hook) => (
                  <li key={hook.id} className="flex items-center justify-between gap-3 py-2">
                    <div className="min-w-0">
                      <p className="truncate font-mono text-slate-200">{hook.url}</p>
                      <p className="text-xs text-slate-400">
                        {hook.lastDeliveredAt === null
                          ? 'Not delivered yet'
                          : hook.lastError
                            ? `Last delivery failed: ${hook.lastError}`
                            : `Last delivery answered ${hook.lastStatus}`}
                      </p>
                    </div>
                    <Button
                      variant="ghost"
                      size="sm"
                      aria-label={`Delete webhook ${hook.url}`}
                      leftIcon={<Trash2 className="h-4 w-4" />}
                      loading={remove.isPending && remove.variables === hook.id}
                      onClick={() => remove.mutate(hook.id)}
                    >
                      Delete
                    </Button>
                  </li>
                ))}
              </ul>
            )}

            {secret && (
              <div role="status" className="rounded-xl border border-amber-700 p-3 text-amber-200">
                <p>Signing secret - copy it now, it cannot be shown again:</p>
                <p className="mt-1 font-mono break-all text-amber-100">{secret}</p>
              </div>
            )}

            <form
              onSubmit={submit}
              aria-label="New webhook"
              className="flex flex-wrap items-end gap-3"
            >
              <div className="min-w-64 flex-1">
                <Input
                  label="Payload URL"
                  type="url"
                  placeholder="https://ci.example.com/hooks/devforge"
                  maxLength={2000}
                  value={url}
                  onChange={(e) => setUrl(e.target.value)}
                />
              </div>
              <Button type="submit" loading={create.isPending} disabled={!url.trim()}>
                Add webhook
              </Button>
            </form>
            {create.isError && (
              <p role="alert" className="text-red-300">
                {describeApiError(create.error)}
              </p>
            )}
            {remove.isError && (
              <p role="alert" className="text-red-300">
                {describeApiError(remove.error)}
              </p>
            )}
          </>
        )}
      </section>
    </Card>
  );
}
