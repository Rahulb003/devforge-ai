import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, Play, TerminalSquare } from 'lucide-react';
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';

import type { ExecutionDetail, ExecutionLanguage } from '@/api/execution.api';
import { EXECUTION_STATUS_LABELS, executionApi } from '@/api/execution.api';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { EmptyState, ErrorState, LoadingState } from '@/components/ui/states';
import { describeApiError } from '@/lib/errors';

const SAMPLES: Record<ExecutionLanguage, string> = {
  PYTHON: 'print("Hello from the sandbox")',
  JAVASCRIPT: 'console.log("Hello from the sandbox")',
};

/**
 * Running a snippet in the project's sandbox.
 *
 * Output is rendered inside <pre> as plain text: it is whatever the program printed, so it is
 * attacker-controlled and must never be interpreted as markup.
 */
export function RunCodePage() {
  const { organizationId = '', projectId = '' } = useParams();
  const queryClient = useQueryClient();
  const [language, setLanguage] = useState<ExecutionLanguage>('PYTHON');
  const [code, setCode] = useState(SAMPLES.PYTHON);
  const [result, setResult] = useState<ExecutionDetail | null>(null);

  const history = useQuery({
    queryKey: ['executions', projectId],
    queryFn: async () => (await executionApi.list(organizationId, projectId)).data.data,
    enabled: Boolean(organizationId && projectId),
  });

  const run = useMutation({
    mutationFn: async () =>
      (await executionApi.run(organizationId, projectId, language, code)).data.data,
    onSuccess: (data) => setResult(data),
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['executions', projectId] }),
  });

  const open = useMutation({
    mutationFn: async (id: string) =>
      (await executionApi.get(organizationId, projectId, id)).data.data,
    onSuccess: (data) => {
      setResult(data);
      setLanguage(data.language);
      setCode(data.code);
    },
  });

  const changeLanguage = (next: ExecutionLanguage) => {
    if (code === SAMPLES[language]) setCode(SAMPLES[next]);
    setLanguage(next);
  };

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
        <h1 className="text-2xl font-semibold text-white">Run code</h1>
        <p className="mt-1 max-w-2xl text-sm text-slate-400">
          Each run gets a fresh, isolated container: no network, no access to anything outside it,
          and limits on time, memory and output. Every run is recorded for the project.
        </p>
      </header>

      <Card>
        <div className="space-y-4">
          <div className="flex flex-wrap items-center gap-3">
            <label htmlFor="run-language" className="text-sm text-slate-300">
              Language
            </label>
            <select
              id="run-language"
              value={language}
              onChange={(e) => changeLanguage(e.target.value as ExecutionLanguage)}
              className="h-10 rounded-lg border border-slate-700 bg-slate-900 px-3 text-sm text-slate-100"
            >
              <option value="PYTHON">Python 3.12</option>
              <option value="JAVASCRIPT">JavaScript (Node 22)</option>
            </select>
          </div>
          <label htmlFor="run-code" className="sr-only">
            Code
          </label>
          <textarea
            id="run-code"
            value={code}
            onChange={(e) => setCode(e.target.value)}
            spellCheck={false}
            rows={12}
            className="w-full rounded-lg border border-slate-700 bg-slate-950 p-3 font-mono text-sm text-slate-100"
          />
          <Button
            leftIcon={<Play className="h-4 w-4" />}
            loading={run.isPending}
            disabled={!code.trim()}
            onClick={() => run.mutate()}
          >
            Run
          </Button>
          {run.isError && (
            <p role="alert" className="text-sm text-red-400">
              {describeApiError(run.error)}
            </p>
          )}
        </div>
      </Card>

      {result && <ResultCard result={result} />}

      <section aria-labelledby="run-history" className="space-y-3">
        <h2 id="run-history" className="text-lg font-semibold text-white">
          Recent runs
        </h2>
        {history.isLoading ? (
          <LoadingState label="Loading runs…" />
        ) : history.isError ? (
          <ErrorState message={describeApiError(history.error)} onRetry={() => history.refetch()} />
        ) : !history.data?.length ? (
          <EmptyState
            icon={<TerminalSquare className="h-6 w-6" />}
            title="No runs yet"
            description="Runs by anyone in this project appear here."
          />
        ) : (
          <ul className="divide-y divide-slate-800 rounded-xl border border-slate-800">
            {history.data.map((e) => (
              <li key={e.id}>
                <button
                  type="button"
                  onClick={() => open.mutate(e.id)}
                  className="flex w-full items-center justify-between gap-4 px-4 py-3 text-left text-sm hover:bg-slate-800/50"
                >
                  <span className="text-slate-200">
                    {e.language === 'PYTHON' ? 'Python' : 'JavaScript'} ·{' '}
                    {EXECUTION_STATUS_LABELS[e.status]}
                    {e.exitCode !== null && ` (exit ${e.exitCode})`}
                  </span>
                  <span className="text-slate-500">{new Date(e.createdAt).toLocaleString()}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

function ResultCard({ result }: { result: ExecutionDetail }) {
  return (
    <Card>
      <div className="space-y-3" data-testid="run-result">
        <p className="text-sm text-slate-300">
          <span className="font-medium text-white">{EXECUTION_STATUS_LABELS[result.status]}</span>
          {result.exitCode !== null && ` · exit code ${result.exitCode}`}
          {result.durationMs !== null && ` · ${result.durationMs} ms`}
          {` · limits ${result.timeoutSeconds}s, ${result.memoryMb} MB`}
        </p>
        {result.truncated && (
          <p className="text-sm text-amber-400">Output was cut off at the limit.</p>
        )}
        <Output label="Output" text={result.stdout} />
        {result.stderr && <Output label="Errors" text={result.stderr} />}
      </div>
    </Card>
  );
}

function Output({ label, text }: { label: string; text: string | null }) {
  return (
    <div>
      <h3 className="mb-1 text-xs font-medium uppercase tracking-wide text-slate-500">{label}</h3>
      <pre className="max-h-80 overflow-auto rounded-lg bg-slate-950 p-3 font-mono text-sm whitespace-pre-wrap text-slate-100">
        {text || '(none)'}
      </pre>
    </div>
  );
}
