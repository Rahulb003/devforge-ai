import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, Play, Workflow } from 'lucide-react';
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';

import type { PipelineRun, PipelineStep } from '@/api/pipeline.api';
import { isFinished, pipelineApi } from '@/api/pipeline.api';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { Input } from '@/components/ui/Input';
import { EmptyState, ErrorState, LoadingState } from '@/components/ui/states';
import { describeApiError } from '@/lib/errors';

const STATUS_STYLE: Record<string, string> = {
  SUCCEEDED: 'text-emerald-400',
  FAILED: 'text-red-400',
  TIMED_OUT: 'text-red-400',
  MEMORY_EXCEEDED: 'text-red-400',
  OUTPUT_EXCEEDED: 'text-red-400',
  ERROR: 'text-amber-400',
  REFUSED: 'text-amber-400',
  INTERRUPTED: 'text-amber-400',
};

const label = (status: string) =>
  status.charAt(0) + status.slice(1).toLowerCase().replace('_', ' ');

/**
 * A repository's pipelines: the steps in its .devforge/pipeline.yml, run in the sandbox.
 * A selected run is polled until it finishes.
 */
export function PipelinesPage() {
  const { organizationId = '', projectId = '', repositoryId = '' } = useParams();
  const queryClient = useQueryClient();
  const [ref, setRef] = useState('');
  const [selected, setSelected] = useState<string | null>(null);

  const runs = useQuery({
    queryKey: ['pipelines', repositoryId],
    queryFn: async () =>
      (await pipelineApi.list(organizationId, projectId, repositoryId)).data.data,
    enabled: Boolean(repositoryId),
  });

  const run = useQuery({
    queryKey: ['pipeline', selected],
    queryFn: async () =>
      (await pipelineApi.get(organizationId, projectId, repositoryId, selected!)).data.data,
    enabled: Boolean(selected),
    refetchInterval: (query) =>
      query.state.data && isFinished(query.state.data.status) ? false : 1500,
  });

  const trigger = useMutation({
    mutationFn: async () =>
      (await pipelineApi.trigger(organizationId, projectId, repositoryId, ref.trim())).data.data,
    onSuccess: (data) => {
      setSelected(data.id);
      queryClient.setQueryData(['pipeline', data.id], data);
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['pipelines', repositoryId] }),
  });

  return (
    <div className="space-y-6">
      <Link
        to={`/organizations/${organizationId}/projects/${projectId}/repositories/${repositoryId}`}
        className="inline-flex items-center gap-2 text-sm text-slate-400 underline-offset-4 hover:text-slate-200 hover:underline"
      >
        <ArrowLeft className="h-4 w-4" aria-hidden="true" />
        Back to repository
      </Link>

      <header>
        <h1 className="text-2xl font-semibold text-white">Pipelines</h1>
        <p className="mt-1 max-w-2xl text-sm text-slate-400">
          Runs the steps in the repository&apos;s <code>.devforge/pipeline.yml</code>, each in its
          own isolated container with the repository&apos;s files. Stops at the first step that
          fails. Nothing is deployed.
        </p>
      </header>

      <Card>
        <form
          className="flex flex-wrap items-end gap-3"
          onSubmit={(e) => {
            e.preventDefault();
            trigger.mutate();
          }}
        >
          <div className="min-w-56 flex-1">
            <Input
              label="Branch or commit"
              placeholder="Default branch"
              value={ref}
              onChange={(e) => setRef(e.target.value)}
            />
          </div>
          <Button type="submit" leftIcon={<Play className="h-4 w-4" />} loading={trigger.isPending}>
            Run pipeline
          </Button>
        </form>
        {trigger.isError && (
          <p role="alert" className="mt-3 text-sm text-red-400">
            {describeApiError(trigger.error)}
          </p>
        )}
      </Card>

      {selected && run.data && <RunCard run={run.data} />}

      <section aria-labelledby="pipeline-history" className="space-y-3">
        <h2 id="pipeline-history" className="text-lg font-semibold text-white">
          Runs
        </h2>
        {runs.isLoading ? (
          <LoadingState label="Loading runs…" />
        ) : runs.isError ? (
          <ErrorState message={describeApiError(runs.error)} onRetry={() => runs.refetch()} />
        ) : !runs.data?.length ? (
          <EmptyState
            icon={<Workflow className="h-6 w-6" />}
            title="No pipeline runs yet"
            description="Add .devforge/pipeline.yml to the repository, then run it here."
          />
        ) : (
          <ul className="divide-y divide-slate-800 rounded-xl border border-slate-800">
            {runs.data.map((r) => (
              <li key={r.id}>
                <button
                  type="button"
                  onClick={() => setSelected(r.id)}
                  className="flex w-full items-center justify-between gap-4 px-4 py-3 text-left text-sm hover:bg-slate-800/50"
                >
                  <span className="text-slate-200">
                    <span className={STATUS_STYLE[r.status] ?? 'text-slate-300'}>
                      {label(r.status)}
                    </span>{' '}
                    · {r.ref}
                  </span>
                  <span className="text-slate-500">{new Date(r.createdAt).toLocaleString()}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

function RunCard({ run }: { run: PipelineRun }) {
  return (
    <Card>
      <div className="space-y-4" data-testid="pipeline-run">
        <p className="text-sm text-slate-300">
          <span className={`font-medium ${STATUS_STYLE[run.status] ?? 'text-white'}`}>
            {label(run.status)}
          </span>{' '}
          · {run.ref}
          {run.message && ` · ${run.message}`}
        </p>
        {run.omittedFiles.length > 0 && (
          <p className="text-sm text-amber-400">
            Not in the workspace (binary or too large): {run.omittedFiles.join(', ')}
          </p>
        )}
        <ol className="space-y-3">
          {run.steps.map((step) => (
            <StepItem key={step.position} step={step} />
          ))}
        </ol>
      </div>
    </Card>
  );
}

function StepItem({ step }: { step: PipelineStep }) {
  const output = [step.stdout, step.stderr].filter(Boolean).join('\n');
  return (
    <li className="rounded-lg border border-slate-800 p-3">
      <p className="text-sm text-slate-200">
        {step.position}. <span className="font-medium">{step.name}</span> ·{' '}
        <span className={STATUS_STYLE[step.status] ?? 'text-slate-400'}>{label(step.status)}</span>
        {step.exitCode !== null && ` (exit ${step.exitCode})`}
        {step.durationMs !== null && ` · ${step.durationMs} ms`}
      </p>
      <pre className="mt-1 font-mono text-xs text-slate-500">$ {step.script}</pre>
      {output && (
        <pre className="mt-2 max-h-72 overflow-auto rounded bg-slate-950 p-2 font-mono text-xs whitespace-pre-wrap text-slate-100">
          {output}
        </pre>
      )}
      {step.truncated && (
        <p className="mt-1 text-xs text-amber-400">Output was cut off at the limit.</p>
      )}
    </li>
  );
}
