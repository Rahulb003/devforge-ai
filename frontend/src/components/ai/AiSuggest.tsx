import { useMutation, useQuery } from '@tanstack/react-query';
import { Wand2 } from 'lucide-react';
import { useEffect, useState, type FormEvent } from 'react';

import { aiApi } from '@/api/ai.api';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { Input } from '@/components/ui/Input';
import { describeApiError } from '@/lib/errors';

/**
 * Asks the model for a changed version of the file, then hands it to the editor.
 *
 * The proposal is never committed from here: "Open in editor" puts it in the working set, where the
 * usual review, edit and commit apply - including the check that nobody else changed the branch
 * meanwhile. Hidden where AI assistance is not configured.
 */
export function AiSuggest({
  organizationId,
  projectId,
  repositoryId,
  path,
  gitRef,
  onUse,
}: {
  organizationId: string;
  projectId: string;
  repositoryId: string;
  path: string;
  gitRef: string | undefined;
  onUse: (proposed: string) => void;
}) {
  const [request, setRequest] = useState('');
  const status = useQuery({
    queryKey: ['ai-status'],
    queryFn: async () => (await aiApi.status()).data.data,
    staleTime: 5 * 60_000,
  });
  const suggest = useMutation({
    mutationFn: async () =>
      (await aiApi.suggest(organizationId, projectId, repositoryId, path, gitRef, request)).data
        .data,
  });
  const { reset } = suggest;
  useEffect(() => reset(), [path, gitRef, reset]);

  if (!status.data?.configured) {
    return null;
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    if (request.trim()) suggest.mutate();
  }

  return (
    <div className="space-y-3">
      <form
        onSubmit={submit}
        aria-label="Change request"
        className="flex flex-wrap items-end gap-3"
      >
        <div className="min-w-64 flex-1">
          <Input
            label="Describe a change to this file"
            placeholder="e.g. add input validation to the exported function"
            maxLength={1000}
            value={request}
            onChange={(e) => setRequest(e.target.value)}
          />
        </div>
        <Button
          type="submit"
          variant="secondary"
          leftIcon={<Wand2 className="h-4 w-4" />}
          loading={suggest.isPending}
          disabled={!request.trim()}
        >
          Suggest change
        </Button>
      </form>
      {suggest.isError && (
        <p role="alert" className="text-sm text-red-400">
          {describeApiError(suggest.error)}
        </p>
      )}
      {suggest.isSuccess && (
        <Card>
          <div role="region" aria-label="Suggested change" className="space-y-3">
            <p className="text-xs text-slate-400">
              Proposed by {suggest.data.model}. Nothing has changed yet: open it in the editor to
              review, adjust and commit it - or leave it.
            </p>
            <pre className="max-h-96 overflow-auto rounded-lg bg-slate-950 p-3 text-xs text-slate-200">
              {suggest.data.proposed}
            </pre>
            <Button size="sm" onClick={() => onUse(suggest.data.proposed)}>
              Open in editor
            </Button>
          </div>
        </Card>
      )}
    </div>
  );
}
