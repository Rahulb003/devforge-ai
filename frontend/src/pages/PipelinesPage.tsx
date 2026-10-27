import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, Play, Workflow } from 'lucide-react';
import { useEffect, useState } from 'react';
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

  // The list showed a run as still running after its own detail said it had ended: only the
  // detail is polled. Refresh the list once, when the run being watched finishes.
  const runStatus = run.data?.status;
  useEffect(() => {
    if (runStatus && isFinished(runStatus)) {
      void queryClient.invalidateQueries({ queryKey: ['pipelines', repositoryId] });
    }
  }, [runStatus, queryClient, repositoryId]);

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

type Look = { ring: string; fill: string; glyph: string; track: string; pulse: string };

const LOOKS: Record<PipelineStep['status'], Look> = {
  SUCCEEDED: {
    ring: 'border-teal-400',
    fill: 'bg-teal-400 text-teal-950',
    glyph: '✓',
    track: 'bg-teal-400',
    pulse: '',
  },
  FAILED: {
    ring: 'border-red-400',
    fill: 'bg-slate-950 text-red-300',
    glyph: '!',
    track: 'bg-teal-400',
    pulse: 'forge-alarm',
  },
  TIMED_OUT: {
    ring: 'border-red-400',
    fill: 'bg-slate-950 text-red-300',
    glyph: '⏱',
    track: 'bg-teal-400',
    pulse: 'forge-alarm',
  },
  MEMORY_EXCEEDED: {
    ring: 'border-red-400',
    fill: 'bg-slate-950 text-red-300',
    glyph: '!',
    track: 'bg-teal-400',
    pulse: 'forge-alarm',
  },
  OUTPUT_EXCEEDED: {
    ring: 'border-red-400',
    fill: 'bg-slate-950 text-red-300',
    glyph: '!',
    track: 'bg-teal-400',
    pulse: 'forge-alarm',
  },
  REFUSED: {
    ring: 'border-amber-400',
    fill: 'bg-slate-950 text-amber-300',
    glyph: '–',
    track: 'bg-teal-400',
    pulse: '',
  },
  RUNNING: {
    ring: 'border-forge-400',
    fill: 'bg-forge-400 text-on-forge',
    glyph: '…',
    track: 'bg-teal-400',
    pulse: 'forge-running',
  },
  PENDING: {
    ring: 'border-slate-600 border-dashed',
    fill: 'bg-slate-950 text-slate-500',
    glyph: '',
    track: 'forge-track-idle',
    pulse: '',
  },
  SKIPPED: {
    ring: 'border-slate-600 border-dashed',
    fill: 'bg-slate-950 text-slate-500',
    glyph: '',
    track: 'forge-track-idle',
    pulse: '',
  },
};

const STAMP: Partial<Record<PipelineRun['status'], { text: string; style: string }>> = {
  SUCCEEDED: { text: 'PASSED', style: 'border-teal-400 text-teal-300' },
  FAILED: { text: 'FAILED', style: 'border-red-400 text-red-300' },
  ERROR: { text: 'ERROR', style: 'border-amber-400 text-amber-300' },
  INTERRUPTED: { text: 'STOPPED', style: 'border-amber-400 text-amber-300' },
};

/** The station worth looking at first: what broke, else what is running, else the end of the line. */
function firstFocus(steps: PipelineStep[]) {
  const broken = steps.find(
    (s) => !['SUCCEEDED', 'RUNNING', 'PENDING', 'SKIPPED'].includes(s.status),
  );
  const running = steps.find((s) => s.status === 'RUNNING');
  return (broken ?? running ?? steps[steps.length - 1])?.position ?? 1;
}

/**
 * A run as a line map: each step a station, the track solid up to where the run got and dashed
 * after. Stations are real buttons, so the map works from the keyboard; the terminal below shows
 * the picked station's output, as plain text - it is whatever the step printed.
 */
function RunCard({ run }: { run: PipelineRun }) {
  const [picked, setPicked] = useState<number | null>(null);
  const focus = picked ?? firstFocus(run.steps);
  const current = run.steps.find((s) => s.position === focus) ?? run.steps[0];
  const stamp = STAMP[run.status];
  const output = current ? [current.stdout, current.stderr].filter(Boolean).join('\n') : '';

  return (
    <Card className="overflow-hidden">
      <div className="space-y-6" data-testid="pipeline-run">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <p className="text-sm text-slate-300">
            <span className={`font-medium ${STATUS_STYLE[run.status] ?? 'text-white'}`}>
              {label(run.status)}
            </span>{' '}
            · <span className="font-mono">{run.ref}</span>
            {run.message && ` · ${run.message}`}
          </p>
          {stamp && (
            <span
              aria-hidden="true"
              className={`forge-stamp rounded-xl border-[3px] px-4 py-1.5 font-display text-xl font-bold tracking-[0.14em] ${stamp.style}`}
            >
              {stamp.text}
            </span>
          )}
        </div>

        {run.omittedFiles.length > 0 && (
          <p className="text-sm text-amber-400">
            Not in the workspace (binary or too large): {run.omittedFiles.join(', ')}
          </p>
        )}

        <div className="overflow-x-auto pb-2">
          <ol className="flex min-w-max items-start" aria-label="Steps">
            {run.steps.map((step, i) => {
              const look = LOOKS[step.status];
              const selected = step.position === current?.position;
              return (
                <li key={step.position} className="flex items-start">
                  {i > 0 && (
                    <span
                      aria-hidden="true"
                      className={`mt-[19px] h-1.5 w-16 rounded-full sm:w-24 ${look.track}`}
                    />
                  )}
                  <button
                    type="button"
                    onClick={() => setPicked(step.position)}
                    aria-pressed={selected}
                    className="flex w-32 flex-col items-center gap-2 rounded-xl pb-1 focus-visible:outline-solid focus-visible:outline-2 focus-visible:outline-indigo-400"
                  >
                    <span
                      className={`grid h-11 w-11 place-items-center rounded-full border-[5px] text-sm font-bold ${look.ring} ${look.fill} ${look.pulse} ${
                        selected ? 'ring-4 ring-indigo-400/50' : ''
                      }`}
                      aria-hidden="true"
                    >
                      {look.glyph}
                    </span>
                    <span className="text-sm font-semibold text-slate-100">{step.name}</span>
                    <span className="font-mono text-xs whitespace-nowrap text-slate-400">
                      {label(step.status)}
                      {step.durationMs !== null && ` · ${(step.durationMs / 1000).toFixed(1)} s`}
                    </span>
                  </button>
                </li>
              );
            })}
          </ol>
        </div>

        {current && (
          <section
            aria-label={`Output of step ${current.position}, ${current.name}`}
            className="overflow-hidden rounded-2xl border border-slate-800 bg-slate-950"
          >
            <div className="flex flex-wrap items-center gap-2 border-b border-slate-800 bg-slate-900/80 px-4 py-2.5">
              <span className="h-2.5 w-2.5 rounded-full bg-red-400" aria-hidden="true" />
              <span className="h-2.5 w-2.5 rounded-full bg-amber-400" aria-hidden="true" />
              <span className="h-2.5 w-2.5 rounded-full bg-emerald-400" aria-hidden="true" />
              <span className="ml-2 font-mono text-xs text-slate-300">
                step {current.position} · {current.name}
                {current.exitCode !== null && ` · exit ${current.exitCode}`}
              </span>
            </div>
            <pre className="m-0 max-h-80 overflow-auto px-4 pt-3 font-mono text-xs text-slate-500">
              $ {current.script}
            </pre>
            <pre className="m-0 max-h-80 min-h-24 overflow-auto px-4 pt-2 pb-4 font-mono text-[13px] leading-relaxed whitespace-pre-wrap text-slate-100">
              {output ||
                (current.status === 'SKIPPED'
                  ? 'Skipped: an earlier step did not succeed.'
                  : current.status === 'PENDING'
                    ? 'Waiting to run.'
                    : '(no output)')}
            </pre>
            {current.truncated && (
              <p className="px-4 pb-3 text-xs text-amber-400">Output was cut off at the limit.</p>
            )}
          </section>
        )}
      </div>
    </Card>
  );
}
