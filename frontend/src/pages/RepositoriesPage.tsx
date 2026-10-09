import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, GitBranch, Plus } from 'lucide-react';
import { useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router-dom';

import { gitApi } from '@/api/git.api';
import { projectApi } from '@/api/project.api';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { Input } from '@/components/ui/Input';
import { EmptyState, ErrorState, LoadingState } from '@/components/ui/states';
import { describeApiError } from '@/lib/errors';

export function RepositoriesPage() {
  const { organizationId = '', projectId = '' } = useParams();
  const queryClient = useQueryClient();

  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [error, setError] = useState<string | null>(null);

  const project = useQuery({
    queryKey: ['project', organizationId, projectId],
    queryFn: async () => (await projectApi.getProject(organizationId, projectId)).data.data,
    enabled: Boolean(organizationId && projectId),
  });

  const repositories = useQuery({
    queryKey: ['repositories', organizationId, projectId],
    queryFn: async () => (await gitApi.list(organizationId, projectId)).data.data,
    enabled: Boolean(organizationId && projectId),
  });

  const create = useMutation({
    mutationFn: () =>
      gitApi.create(organizationId, projectId, {
        name: name.trim(),
        description: description.trim() || undefined,
      }),
    onSuccess: () => {
      setCreating(false);
      setName('');
      setDescription('');
      setError(null);
      void queryClient.invalidateQueries({ queryKey: ['repositories', organizationId, projectId] });
    },
    // The server rejects names that are unsafe as a directory, so its message is
    // more useful than anything guessed here.
    onError: (err) => setError(describeApiError(err)),
  });

  const items = repositories.data?.content ?? [];

  return (
    <div className="space-y-6">
      <Link
        to={`/organizations/${organizationId}/projects/${projectId}`}
        className="inline-flex items-center gap-2 text-sm text-slate-400 underline-offset-4 hover:text-slate-200 hover:underline"
      >
        <ArrowLeft className="h-4 w-4" aria-hidden="true" />
        Back to board
      </Link>

      <header className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-white">Repositories</h1>
          <p className="mt-1 text-sm text-slate-400">
            {project.data?.name ? `In ${project.data.name}. ` : ''}
            Hosted by DevForge itself — not linked to GitHub.
          </p>
        </div>
        {!creating && (
          <Button leftIcon={<Plus className="h-4 w-4" />} onClick={() => setCreating(true)}>
            New repository
          </Button>
        )}
      </header>

      {error && (
        <div
          role="alert"
          className="rounded-xl border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-300"
        >
          {error}
        </div>
      )}

      {creating && (
        <Card>
          <form
            onSubmit={(event: FormEvent) => {
              event.preventDefault();
              if (name.trim()) create.mutate();
            }}
            className="space-y-4"
          >
            <h2 className="text-lg font-semibold text-white">Create a repository</h2>
            <Input
              label="Name"
              value={name}
              onChange={(event) => setName(event.target.value)}
              placeholder="payments-api"
              hint="Letters, digits, dots, underscores and hyphens."
            />
            <Input
              label="Description"
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              placeholder="Optional"
            />
            <div className="flex gap-3">
              <Button type="submit" loading={create.isPending} disabled={!name.trim()}>
                Create
              </Button>
              <Button
                type="button"
                variant="secondary"
                onClick={() => {
                  setCreating(false);
                  setError(null);
                }}
              >
                Cancel
              </Button>
            </div>
          </form>
        </Card>
      )}

      {repositories.isLoading && <LoadingState label="Loading repositories…" />}

      {repositories.isError && (
        <ErrorState
          message={describeApiError(repositories.error)}
          onRetry={() => void repositories.refetch()}
        />
      )}

      {repositories.isSuccess && items.length === 0 && !creating && (
        <EmptyState
          icon={<GitBranch className="h-6 w-6" aria-hidden="true" />}
          title="No repositories yet"
          description="Create one to start committing code. You can browse files, history and diffs here."
        />
      )}

      {items.length > 0 && (
        <ul className="grid gap-4 sm:grid-cols-2">
          {items.map((repository) => (
            <li key={repository.id}>
              <Link
                to={`/organizations/${organizationId}/projects/${projectId}/repositories/${repository.id}`}
                className="block rounded-2xl border border-slate-800 bg-slate-900 p-5 transition hover:border-slate-700 focus-visible:outline-solid focus-visible:outline-2 focus-visible:outline-indigo-400"
              >
                <div className="flex items-start justify-between gap-3">
                  <h2 className="font-semibold text-white">{repository.name}</h2>
                  {repository.empty ? (
                    <Badge tone="warning">Empty</Badge>
                  ) : (
                    <Badge tone="success">{repository.defaultBranch}</Badge>
                  )}
                </div>
                {repository.description && (
                  <p className="mt-2 line-clamp-2 text-sm text-slate-400">
                    {repository.description}
                  </p>
                )}
              </Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
