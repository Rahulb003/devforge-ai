import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ArrowLeft,
  CheckCircle2,
  FileWarning,
  KeyRound,
  GitMerge,
  ShieldAlert,
  Wrench,
  XCircle,
} from 'lucide-react';
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';

import type { Finding, FindingCategory, Review, Severity } from '@/api/review.api';
import { CATEGORY_LABELS, reviewApi, SEVERITY_LABELS } from '@/api/review.api';
import { gitApi } from '@/api/git.api';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { EmptyState, ErrorState, LoadingState, Skeleton } from '@/components/ui/states';
import { describeApiError } from '@/lib/errors';

/** Blocker and high are the ones that stop a merge, so they read as danger. */
const SEVERITY_TONE: Record<Severity, 'danger' | 'warning' | 'info' | 'neutral'> = {
  BLOCKER: 'danger',
  HIGH: 'danger',
  MEDIUM: 'warning',
  LOW: 'info',
  INFO: 'neutral',
};

const CATEGORY_ICON: Record<FindingCategory, typeof KeyRound> = {
  SECRET: KeyRound,
  CREDENTIAL_FILE: ShieldAlert,
  MERGE_CONFLICT: GitMerge,
  DANGEROUS_PATTERN: FileWarning,
  MAINTAINABILITY: Wrench,
};

export function ReviewPage() {
  const { organizationId = '', projectId = '', repositoryId = '' } = useParams();
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);

  const repository = useQuery({
    queryKey: ['repository', organizationId, projectId, repositoryId],
    queryFn: async () => (await gitApi.get(organizationId, projectId, repositoryId)).data.data,
    enabled: Boolean(organizationId && projectId && repositoryId),
  });

  /**
   * The newest review.
   *
   * A 404 here means "never reviewed", which is a normal state rather than a failure — so it is
   * not retried and not surfaced as an error.
   */
  const latest = useQuery({
    queryKey: ['review-latest', repositoryId],
    queryFn: async () => {
      try {
        return (await reviewApi.latest(organizationId, projectId, repositoryId)).data.data;
      } catch (err) {
        if ((err as { response?: { status?: number } })?.response?.status === 404) return null;
        throw err;
      }
    },
    enabled: Boolean(repositoryId),
    retry: false,
  });

  const findings = useQuery({
    queryKey: ['review-findings', latest.data?.id],
    queryFn: async () =>
      (await reviewApi.findings(organizationId, projectId, repositoryId, latest.data!.id)).data
        .data,
    enabled: Boolean(latest.data?.id),
  });

  const run = useMutation({
    mutationFn: () => reviewApi.run(organizationId, projectId, repositoryId),
    onSuccess: () => {
      setError(null);
      void queryClient.invalidateQueries({ queryKey: ['review-latest'] });
      void queryClient.invalidateQueries({ queryKey: ['review-findings'] });
    },
    // A 503 here means the code could not be read. Saying so matters: the alternative reading is
    // "nothing found", which looks like a pass.
    onError: (err) => setError(describeApiError(err)),
  });

  const review = latest.data;

  return (
    <div className="space-y-6">
      <Link
        to={`/organizations/${organizationId}/projects/${projectId}/repositories/${repositoryId}`}
        className="inline-flex items-center gap-2 text-sm text-slate-400 underline-offset-4 hover:text-slate-200 hover:underline"
      >
        <ArrowLeft className="h-4 w-4" aria-hidden="true" />
        Back to {repository.data?.name ?? 'repository'}
      </Link>

      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-white">Code review</h1>
          <p className="mt-1 max-w-2xl text-sm text-slate-400">
            Static analysis over the committed code: credentials, credential files, merge-conflict
            markers and a few high-risk patterns. Not an AI reviewer.
          </p>
        </div>
        <Button onClick={() => run.mutate()} loading={run.isPending}>
          {review ? 'Run again' : 'Run a review'}
        </Button>
      </header>

      {error && (
        <div
          role="alert"
          className="rounded-xl border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-300"
        >
          {error}
        </div>
      )}

      {latest.isLoading && <LoadingState label="Loading the latest review…" />}

      {latest.isError && (
        <ErrorState
          message={describeApiError(latest.error)}
          onRetry={() => void latest.refetch()}
        />
      )}

      {latest.isSuccess && !review && (
        <EmptyState
          icon={<ShieldAlert className="h-6 w-6" aria-hidden="true" />}
          title="This repository has not been reviewed"
          description="Run a review to check the committed code for credentials, conflict markers and risky patterns."
        />
      )}

      {review && <GateSummary review={review} />}

      {review?.status === 'FAILED' && (
        <div
          role="alert"
          className="rounded-2xl border border-amber-500/40 bg-amber-500/10 px-5 py-4 text-sm text-amber-200"
        >
          <p className="font-semibold">This review could not read the code.</p>
          <p className="mt-1">
            {review.failureReason ?? 'The repository content was unavailable.'}
          </p>
          <p className="mt-2 text-amber-300/80">
            Deliberately not reported as a pass — nothing was analysed, so nothing can be concluded.
          </p>
        </div>
      )}

      {review?.status === 'COMPLETED' && (
        <FindingsList
          isLoading={findings.isLoading}
          isError={findings.isError}
          error={findings.error}
          findings={findings.data ?? []}
          onRetry={() => void findings.refetch()}
          onDismiss={async (findingId, reason) => {
            await reviewApi.dismiss(
              organizationId,
              projectId,
              repositoryId,
              review.id,
              findingId,
              reason,
            );
            void queryClient.invalidateQueries({ queryKey: ['review-findings'] });
          }}
        />
      )}
    </div>
  );
}

function GateSummary({ review }: { review: Review }) {
  const passed = review.gate === 'PASS';
  const counts: Array<[Severity, number]> = [
    ['BLOCKER', review.blockerCount],
    ['HIGH', review.highCount],
    ['MEDIUM', review.mediumCount],
    ['LOW', review.lowCount],
  ];

  return (
    <Card>
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div className="flex items-center gap-4">
          {review.gate === null ? (
            <Skeleton className="h-12 w-12 rounded-full" />
          ) : (
            <div
              className={`grid h-12 w-12 place-items-center rounded-full ${
                passed ? 'bg-emerald-500/10 text-emerald-400' : 'bg-red-500/10 text-red-400'
              }`}
            >
              {passed ? (
                <CheckCircle2 className="h-6 w-6" aria-hidden="true" />
              ) : (
                <XCircle className="h-6 w-6" aria-hidden="true" />
              )}
            </div>
          )}
          <div>
            <p className="text-lg font-semibold text-white">
              {review.gate === null
                ? 'No result'
                : passed
                  ? 'Quality gate passed'
                  : 'Quality gate failed'}
            </p>
            <p className="mt-0.5 text-sm text-slate-400">
              <span className="font-mono">{review.ref}</span>
              {review.baseRef && (
                <>
                  {' compared with '}
                  <span className="font-mono">{review.baseRef}</span>
                </>
              )}
              {' · '}
              {review.filesAnalysed} file{review.filesAnalysed === 1 ? '' : 's'} analysed
            </p>
          </div>
        </div>

        <div className="flex flex-wrap gap-2">
          {counts.map(([severity, count]) => (
            <Badge key={severity} tone={count > 0 ? SEVERITY_TONE[severity] : 'neutral'}>
              {count} {SEVERITY_LABELS[severity].toLowerCase()}
            </Badge>
          ))}
        </div>
      </div>
    </Card>
  );
}

function FindingsList({
  isLoading,
  isError,
  error,
  findings,
  onRetry,
  onDismiss,
}: {
  isLoading: boolean;
  isError: boolean;
  error: unknown;
  findings: Finding[];
  onRetry: () => void;
  onDismiss: (findingId: string, reason: string) => Promise<void>;
}) {
  if (isLoading) return <LoadingState label="Loading findings…" />;
  if (isError) return <ErrorState message={describeApiError(error)} onRetry={onRetry} />;

  if (findings.length === 0) {
    return (
      <EmptyState
        icon={<CheckCircle2 className="h-6 w-6" aria-hidden="true" />}
        title="No findings"
        description="Nothing matched the analysis rules in the files that were analysed."
      />
    );
  }

  return (
    <ul className="space-y-3">
      {findings.map((finding) => (
        <FindingRow key={finding.id} finding={finding} onDismiss={onDismiss} />
      ))}
    </ul>
  );
}

function FindingRow({
  finding,
  onDismiss,
}: {
  finding: Finding;
  onDismiss: (findingId: string, reason: string) => Promise<void>;
}) {
  const [dismissing, setDismissing] = useState(false);
  const [reason, setReason] = useState('');
  const [pending, setPending] = useState(false);
  const [rowError, setRowError] = useState<string | null>(null);

  const Icon = CATEGORY_ICON[finding.category] ?? FileWarning;

  async function submit() {
    setPending(true);
    setRowError(null);
    try {
      await onDismiss(finding.id, reason.trim());
      setDismissing(false);
      setReason('');
    } catch (err) {
      setRowError(describeApiError(err));
    } finally {
      setPending(false);
    }
  }

  return (
    <li
      className={`rounded-2xl border p-4 ${
        finding.dismissed
          ? 'border-slate-800 bg-slate-900/50 opacity-70'
          : 'border-slate-700 bg-slate-900'
      }`}
    >
      <div className="flex gap-4">
        <div
          className={`grid h-10 w-10 shrink-0 place-items-center rounded-xl ${
            finding.severity === 'BLOCKER' || finding.severity === 'HIGH'
              ? 'bg-red-500/10 text-red-400'
              : 'bg-amber-500/10 text-amber-300'
          }`}
        >
          <Icon className="h-5 w-5" aria-hidden="true" />
        </div>

        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <Badge tone={SEVERITY_TONE[finding.severity]}>
              {SEVERITY_LABELS[finding.severity]}
            </Badge>
            <span className="text-sm text-slate-400">{CATEGORY_LABELS[finding.category]}</span>
            {finding.dismissed && <Badge tone="neutral">Dismissed</Badge>}
          </div>

          <p className="mt-2 font-mono text-sm text-slate-300">
            {finding.filePath}
            {finding.lineNumber !== null && (
              <span className="text-slate-500">:{finding.lineNumber}</span>
            )}
          </p>

          <p className="mt-2 text-sm text-slate-400">{finding.message}</p>

          {finding.snippet && (
            // Already redacted server-side where it held a credential, so this never shows a secret.
            <pre className="mt-3 overflow-x-auto rounded-xl bg-slate-950 px-4 py-3 font-mono text-xs text-slate-300">
              {finding.snippet}
            </pre>
          )}

          {finding.dismissed && finding.dismissReason && (
            <p className="mt-3 text-xs text-slate-500">Dismissed: {finding.dismissReason}</p>
          )}

          {rowError && (
            <p role="alert" className="mt-3 text-sm text-red-300">
              {rowError}
            </p>
          )}

          {!finding.dismissed && !dismissing && (
            <button
              type="button"
              onClick={() => setDismissing(true)}
              className="mt-3 rounded-lg px-2 py-1 text-xs font-medium text-slate-400 transition hover:bg-slate-800 hover:text-white focus-visible:outline-solid focus-visible:outline-2 focus-visible:outline-indigo-400"
            >
              Dismiss…
            </button>
          )}

          {dismissing && (
            <div className="mt-3 space-y-2">
              <label
                htmlFor={`reason-${finding.id}`}
                className="block text-xs font-medium text-slate-400"
              >
                Why is this acceptable?
              </label>
              <input
                id={`reason-${finding.id}`}
                value={reason}
                onChange={(event) => setReason(event.target.value)}
                placeholder="e.g. key already rotated"
                className="w-full rounded-xl border border-slate-700 bg-slate-800 px-3 py-2 text-sm text-slate-100 focus-visible:outline-solid focus-visible:outline-2 focus-visible:outline-indigo-400"
              />
              <div className="flex gap-2">
                <Button size="sm" onClick={submit} loading={pending} disabled={!reason.trim()}>
                  Dismiss
                </Button>
                <Button
                  size="sm"
                  variant="secondary"
                  onClick={() => {
                    setDismissing(false);
                    setRowError(null);
                  }}
                >
                  Cancel
                </Button>
              </div>
              <p className="text-xs text-slate-500">
                A reason is required, and dismissing does not change the gate result.
              </p>
            </div>
          )}
        </div>
      </div>
    </li>
  );
}
