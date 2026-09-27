import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Play, Plus, Square } from 'lucide-react';
import { useState, type FormEvent } from 'react';

import { sprintApi, type Sprint } from '@/api/task.api';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { Card, CardHeader } from '@/components/ui/Card';
import { Input } from '@/components/ui/Input';
import { Skeleton } from '@/components/ui/states';
import { describeApiError } from '@/lib/errors';

const statusTone = {
  PLANNED: 'neutral',
  ACTIVE: 'success',
  COMPLETED: 'info',
} as const;

/** Sprint list and lifecycle controls for a project. */
export function SprintPanel({
  organizationId,
  projectId,
}: {
  organizationId: string;
  projectId: string;
}) {
  const queryClient = useQueryClient();
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [error, setError] = useState<string | null>(null);

  const sprints = useQuery({
    queryKey: ['sprints', projectId],
    queryFn: async () => (await sprintApi.list(organizationId, projectId)).data.data,
  });

  function refresh() {
    queryClient.invalidateQueries({ queryKey: ['sprints', projectId] });
  }

  const createSprint = useMutation({
    mutationFn: (value: string) => sprintApi.create(organizationId, projectId, { name: value }),
    onSuccess: () => {
      setName('');
      setCreating(false);
      setError(null);
      refresh();
    },
    onError: (err) => setError(describeApiError(err)),
  });

  const startSprint = useMutation({
    mutationFn: (sprintId: string) => sprintApi.start(organizationId, projectId, sprintId),
    onSuccess: () => {
      setError(null);
      refresh();
    },
    // The server refuses a second active sprint; surface that rather than
    // leaving the button looking broken.
    onError: (err) => setError(describeApiError(err)),
  });

  const completeSprint = useMutation({
    mutationFn: (sprintId: string) => sprintApi.complete(organizationId, projectId, sprintId),
    onSuccess: () => {
      setError(null);
      refresh();
    },
    onError: (err) => setError(describeApiError(err)),
  });

  function progress(sprint: Sprint) {
    if (sprint.totalTasks === 0) return 0;
    return Math.round((sprint.completedTasks / sprint.totalTasks) * 100);
  }

  return (
    <Card as="section" aria-label="Sprints">
      <CardHeader
        title="Sprints"
        description="A time-boxed slice of the backlog. Only one can be active at a time."
        action={
          !creating ? (
            <Button
              size="sm"
              variant="secondary"
              leftIcon={<Plus className="h-4 w-4" />}
              onClick={() => setCreating(true)}
            >
              New sprint
            </Button>
          ) : undefined
        }
      />

      {error && (
        <div
          role="alert"
          className="mb-4 rounded-xl border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-300"
        >
          {error}
        </div>
      )}

      {creating && (
        <form
          onSubmit={(event: FormEvent) => {
            event.preventDefault();
            if (name.trim()) createSprint.mutate(name.trim());
          }}
          className="mb-4 flex gap-2"
        >
          <div className="flex-1">
            <Input
              label="Sprint name"
              value={name}
              onChange={(event) => setName(event.target.value)}
              placeholder="Sprint 1"
              required
            />
          </div>
          <Button type="submit" className="mt-7" loading={createSprint.isPending}>
            Create
          </Button>
          <Button
            type="button"
            variant="ghost"
            className="mt-7"
            onClick={() => {
              setCreating(false);
              setError(null);
            }}
          >
            Cancel
          </Button>
        </form>
      )}

      {sprints.isLoading && <Skeleton className="h-16" />}

      {sprints.isSuccess && sprints.data.length === 0 && !creating && (
        <p className="text-sm text-slate-500">
          No sprints yet. Create one to plan a slice of the backlog.
        </p>
      )}

      {sprints.isSuccess && sprints.data.length > 0 && (
        <ul className="space-y-2">
          {sprints.data.map((sprint) => (
            <li
              key={sprint.id}
              className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-slate-800 bg-slate-950 px-4 py-3"
            >
              <div className="min-w-0">
                <div className="flex items-center gap-2">
                  <p className="font-medium text-slate-200">{sprint.name}</p>
                  <Badge tone={statusTone[sprint.status]}>{sprint.status}</Badge>
                </div>
                <p className="mt-1 text-xs text-slate-500">
                  {sprint.completedTasks} of {sprint.totalTasks} done ({progress(sprint)}%)
                </p>
              </div>

              <div className="flex gap-2">
                {sprint.status === 'PLANNED' && (
                  <Button
                    size="sm"
                    variant="secondary"
                    leftIcon={<Play className="h-4 w-4" />}
                    loading={startSprint.isPending && startSprint.variables === sprint.id}
                    onClick={() => startSprint.mutate(sprint.id)}
                  >
                    Start
                  </Button>
                )}
                {sprint.status === 'ACTIVE' && (
                  <Button
                    size="sm"
                    variant="secondary"
                    leftIcon={<Square className="h-4 w-4" />}
                    loading={completeSprint.isPending && completeSprint.variables === sprint.id}
                    onClick={() => completeSprint.mutate(sprint.id)}
                  >
                    Complete
                  </Button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}
