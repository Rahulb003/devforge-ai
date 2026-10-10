import { useMutation, useQuery } from '@tanstack/react-query';
import { Sparkles } from 'lucide-react';
import { useEffect, useState } from 'react';

import { aiApi } from '@/api/ai.api';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { Input } from '@/components/ui/Input';
import { describeApiError } from '@/lib/errors';

/**
 * Asks the model to explain the file on screen.
 *
 * Where no API key is configured the button is disabled and says so: there is no stand-in answer.
 * The explanation is model output about untrusted repository content, so it is rendered as plain
 * text - never as HTML or Markdown that could carry links or markup into the page.
 */
export function AiExplain({
  organizationId,
  projectId,
  repositoryId,
  path,
  gitRef,
}: {
  organizationId: string;
  projectId: string;
  repositoryId: string;
  path: string;
  gitRef: string | undefined;
}) {
  const status = useQuery({
    queryKey: ['ai-status'],
    queryFn: async () => (await aiApi.status()).data.data,
    staleTime: 5 * 60_000,
  });
  const [question, setQuestion] = useState('');
  const explain = useMutation({
    mutationFn: async () =>
      (await aiApi.explain(organizationId, projectId, repositoryId, path, gitRef, question)).data
        .data,
  });
  const { reset } = explain;

  // A different file is a different question; the previous answer must not linger beside it.
  useEffect(() => reset(), [path, gitRef, reset]);

  const configured = status.data?.configured ?? false;

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-end gap-3">
        {configured && (
          <div className="min-w-64 flex-1">
            <Input
              label="Ask about this file (optional)"
              placeholder="e.g. where is the input validated?"
              maxLength={500}
              value={question}
              onChange={(e) => setQuestion(e.target.value)}
            />
          </div>
        )}
        <Button
          size="sm"
          variant="secondary"
          leftIcon={<Sparkles className="h-4 w-4" />}
          loading={explain.isPending}
          disabled={!configured}
          onClick={() => explain.mutate()}
        >
          Explain with AI
        </Button>
        {status.isSuccess && !configured && (
          <span className="text-xs text-slate-400">
            AI assistance is not configured on this deployment.
          </span>
        )}
      </div>
      {explain.isError && (
        <p role="alert" className="text-sm text-red-400">
          {describeApiError(explain.error)}
        </p>
      )}
      {explain.isSuccess && (
        <Card>
          <section aria-label="AI explanation" className="space-y-2">
            <p className="text-xs text-slate-400">
              Written by {explain.data.model}
              {explain.data.truncated ? ' from the beginning of the file only' : ''}. It can be
              wrong; check it against the code.
            </p>
            <p className="text-sm whitespace-pre-wrap text-slate-200">{explain.data.explanation}</p>
          </section>
        </Card>
      )}
    </div>
  );
}
