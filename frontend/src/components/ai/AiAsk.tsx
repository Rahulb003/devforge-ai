import { useMutation, useQuery } from '@tanstack/react-query';
import { Sparkles } from 'lucide-react';
import { useState, type FormEvent } from 'react';

import { aiApi } from '@/api/ai.api';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { Input } from '@/components/ui/Input';
import { describeApiError } from '@/lib/errors';

/**
 * A question about the whole repository, answered from its best-matching files.
 *
 * The files used are listed with the answer, so it is clear the model saw excerpts of a few files,
 * not the whole repository. Hidden entirely where AI assistance is not configured: a form that can
 * only ever answer "not configured" is noise on every repository page.
 */
export function AiAsk({
  organizationId,
  projectId,
  repositoryId,
  gitRef,
}: {
  organizationId: string;
  projectId: string;
  repositoryId: string;
  gitRef: string | undefined;
}) {
  const [question, setQuestion] = useState('');
  const status = useQuery({
    queryKey: ['ai-status'],
    queryFn: async () => (await aiApi.status()).data.data,
    staleTime: 5 * 60_000,
  });
  const ask = useMutation({
    mutationFn: async () =>
      (await aiApi.ask(organizationId, projectId, repositoryId, question, gitRef)).data.data,
  });

  if (!status.data?.configured) {
    return null;
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    if (question.trim()) ask.mutate();
  }

  return (
    <Card>
      <form
        onSubmit={submit}
        aria-label="Repository question"
        className="flex flex-wrap items-end gap-3"
      >
        <div className="min-w-64 flex-1">
          <Input
            label="Ask about this repository"
            placeholder="e.g. where are tokens validated?"
            maxLength={500}
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
          />
        </div>
        <Button
          type="submit"
          variant="secondary"
          leftIcon={<Sparkles className="h-4 w-4" />}
          loading={ask.isPending}
          disabled={!question.trim()}
        >
          Ask
        </Button>
      </form>
      {ask.isError && (
        <p role="alert" className="mt-3 text-sm text-red-400">
          {describeApiError(ask.error)}
        </p>
      )}
      {ask.isSuccess && (
        <div role="region" aria-label="AI answer" className="mt-4 space-y-2">
          <p className="text-sm whitespace-pre-wrap text-slate-200">{ask.data.answer}</p>
          {ask.data.sources.length > 0 && (
            <p className="text-xs text-slate-400">
              From {ask.data.model}, using {ask.data.sources.join(', ')} - the best matches of{' '}
              {ask.data.filesSearched} files searched{ask.data.truncated ? ', cut short' : ''}. It
              can be wrong.
            </p>
          )}
        </div>
      )}
    </Card>
  );
}
