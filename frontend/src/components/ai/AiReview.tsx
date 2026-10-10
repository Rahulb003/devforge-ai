import { useMutation, useQuery } from '@tanstack/react-query';
import { Sparkles } from 'lucide-react';

import { aiApi } from '@/api/ai.api';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { describeApiError } from '@/lib/errors';

/**
 * Asks the model for a review of the pull request, as advice beside the human review.
 *
 * It approves nothing and posts nothing: the answer is shown here, once, as plain text, and the
 * merge rules still decide. Disabled, with the reason, where no API key is configured.
 */
export function AiReview({
  organizationId,
  projectId,
  repositoryId,
  number,
}: {
  organizationId: string;
  projectId: string;
  repositoryId: string;
  number: number;
}) {
  const status = useQuery({
    queryKey: ['ai-status'],
    queryFn: async () => (await aiApi.status()).data.data,
    staleTime: 5 * 60_000,
  });
  const review = useMutation({
    mutationFn: async () =>
      (await aiApi.review(organizationId, projectId, repositoryId, number)).data.data,
  });
  const configured = status.data?.configured ?? false;

  return (
    <section aria-labelledby="ai-review" className="space-y-3">
      <div className="flex flex-wrap items-center gap-3">
        <h2 id="ai-review" className="text-lg font-semibold text-white">
          AI review
        </h2>
        <Button
          size="sm"
          variant="secondary"
          leftIcon={<Sparkles className="h-4 w-4" />}
          loading={review.isPending}
          disabled={!configured}
          onClick={() => review.mutate()}
        >
          Review with AI
        </Button>
        {status.isSuccess && !configured && (
          <span className="text-xs text-slate-400">
            AI assistance is not configured on this deployment.
          </span>
        )}
      </div>
      {review.isError && (
        <p role="alert" className="text-sm text-red-400">
          {describeApiError(review.error)}
        </p>
      )}
      {review.isSuccess && (
        <Card>
          <div aria-label="AI review result" role="region" className="space-y-2">
            <p className="text-xs text-slate-400">
              Advice from {review.data.model} on {review.data.filesReviewed} of{' '}
              {review.data.filesChanged} changed files{review.data.truncated ? ' (cut short)' : ''}.
              It approves nothing and can be wrong.
            </p>
            <p className="text-sm whitespace-pre-wrap text-slate-200">{review.data.review}</p>
          </div>
        </Card>
      )}
    </section>
  );
}
