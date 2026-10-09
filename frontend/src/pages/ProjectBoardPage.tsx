import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, BarChart3, GitBranch, MessageSquare, Plus } from 'lucide-react';
import { useState, type DragEvent, type FormEvent } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';

import { projectApi } from '@/api/project.api';
import { STATUS_LABELS, TASK_STATUSES, taskApi, type Task, type TaskStatus } from '@/api/task.api';
import { SprintPanel } from '@/components/board/SprintPanel';
import { TaskCard } from '@/components/board/TaskCard';
import { TaskDetailDrawer } from '@/components/board/TaskDetailDrawer';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { Input } from '@/components/ui/Input';
import { ErrorState, Skeleton } from '@/components/ui/states';
import { describeApiError } from '@/lib/errors';
import { cn } from '@/lib/utils';

/**
 * The Kanban board for one project.
 *
 * Cards are dragged with a mouse and moved with a select for everyone else;
 * both call the same endpoint. The whole board arrives in one request, so a
 * drag does not need a round trip per column.
 */
export function ProjectBoardPage() {
  const { organizationId = '', projectId = '' } = useParams();
  const queryClient = useQueryClient();

  const [dragged, setDragged] = useState<Task | null>(null);
  const [dragOverColumn, setDragOverColumn] = useState<TaskStatus | null>(null);
  const [openTask, setOpenTaskState] = useState<Task | null>(null);
  // The open task is mirrored in ?task=, so a notification - or anyone - can link straight to it.
  const [searchParams, setSearchParams] = useSearchParams();
  const linkedTaskId = searchParams.get('task');
  const setOpenTask = (task: Task | null) => {
    setOpenTaskState(task);
    const params = new URLSearchParams(searchParams);
    if (task) params.set('task', task.id);
    else params.delete('task');
    setSearchParams(params, { replace: true });
  };
  const [creating, setCreating] = useState(false);
  const [title, setTitle] = useState('');
  const [error, setError] = useState<string | null>(null);

  const project = useQuery({
    queryKey: ['project', organizationId, projectId],
    queryFn: async () => (await projectApi.getProject(organizationId, projectId)).data.data,
  });

  const board = useQuery({
    queryKey: ['board', projectId],
    queryFn: async () => (await taskApi.board(organizationId, projectId)).data.data,
  });

  function refreshBoard() {
    queryClient.invalidateQueries({ queryKey: ['board', projectId] });
  }

  const createTask = useMutation({
    mutationFn: (value: string) => taskApi.create(organizationId, projectId, { title: value }),
    onSuccess: () => {
      setTitle('');
      setCreating(false);
      setError(null);
      refreshBoard();
    },
    onError: (err) => setError(describeApiError(err)),
  });

  const moveTask = useMutation({
    mutationFn: ({
      task,
      status,
      position,
    }: {
      task: Task;
      status: TaskStatus;
      position?: number;
    }) => taskApi.move(organizationId, projectId, task.id, status, position),
    onSuccess: () => {
      setError(null);
      refreshBoard();
    },
    onError: (err) => {
      setError(describeApiError(err));
      // The card was moved optimistically in the UI only by the server's
      // response, so a refresh restores the true order after a failure.
      refreshBoard();
    },
  });

  function handleDrop(event: DragEvent<HTMLElement>, status: TaskStatus) {
    event.preventDefault();
    setDragOverColumn(null);
    if (!dragged) return;

    // Dropping a card back in its own column with no index is a no-op; avoid
    // the pointless request and the flash of a refetch.
    if (dragged.status === status) {
      setDragged(null);
      return;
    }
    moveTask.mutate({ task: dragged, status });
    setDragged(null);
  }

  const linkedTask =
    linkedTaskId && !openTask
      ? board.data?.flatMap((column) => column.tasks).find((t) => t.id === linkedTaskId)
      : undefined;
  const shownTask = openTask ?? linkedTask ?? null;

  if (project.isError) {
    return (
      <ErrorState
        title="Project not found"
        message="It may not exist, or you may not have access to it."
        onRetry={() => project.refetch()}
      />
    );
  }

  const projectKey = project.data?.projectKey ?? '…';

  return (
    <div className="space-y-6">
      <Link
        to={`/organizations/${organizationId}`}
        className="inline-flex items-center gap-2 text-sm text-slate-400 underline-offset-4 hover:text-slate-200 hover:underline"
      >
        <ArrowLeft className="h-4 w-4" aria-hidden="true" />
        Back to projects
      </Link>

      <header className="flex flex-wrap items-center justify-between gap-4">
        <div>
          {project.isLoading ? (
            <Skeleton className="h-8 w-56" />
          ) : (
            <>
              <div className="flex items-center gap-3">
                <h1 className="text-2xl font-semibold text-white">{project.data?.name}</h1>
                <Badge tone={project.data?.status === 'ACTIVE' ? 'success' : 'warning'}>
                  {project.data?.status}
                </Badge>
              </div>
              <p className="mt-1 font-mono text-sm text-slate-400">{projectKey}</p>
            </>
          )}
        </div>
        <div className="flex items-center gap-3">
          <Link
            to={`/organizations/${organizationId}/projects/${projectId}/repositories`}
            className="inline-flex h-11 items-center gap-2 rounded-xl border border-slate-700 bg-slate-800 px-5 text-sm font-medium text-slate-100 transition hover:border-slate-600 focus-visible:outline-solid focus-visible:outline-2 focus-visible:outline-indigo-400"
          >
            <GitBranch className="h-4 w-4" aria-hidden="true" />
            Repositories
          </Link>
          <Link
            to={`/organizations/${organizationId}/projects/${projectId}/chat`}
            className="inline-flex h-11 items-center gap-2 rounded-xl border border-slate-700 bg-slate-800 px-5 text-sm font-medium text-slate-100 transition hover:border-slate-600 focus-visible:outline-solid focus-visible:outline-2 focus-visible:outline-indigo-400"
          >
            <MessageSquare className="h-4 w-4" aria-hidden="true" />
            Chat
          </Link>
          <Link
            to={`/organizations/${organizationId}/projects/${projectId}/analytics`}
            className="inline-flex h-11 items-center gap-2 rounded-xl border border-slate-700 bg-slate-800 px-5 text-sm font-medium text-slate-100 transition hover:border-slate-600 focus-visible:outline-solid focus-visible:outline-2 focus-visible:outline-indigo-400"
          >
            <BarChart3 className="h-4 w-4" aria-hidden="true" />
            Activity
          </Link>
          {!creating && (
            <Button leftIcon={<Plus className="h-4 w-4" />} onClick={() => setCreating(true)}>
              New task
            </Button>
          )}
        </div>
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
              if (title.trim()) createTask.mutate(title.trim());
            }}
            className="space-y-4"
          >
            <h2 className="text-lg font-semibold text-white">Create a task</h2>
            <Input
              label="Title"
              value={title}
              onChange={(event) => setTitle(event.target.value)}
              placeholder="What needs doing?"
              required
            />
            <div className="flex gap-3">
              <Button type="submit" loading={createTask.isPending}>
                Create
              </Button>
              <Button
                type="button"
                variant="ghost"
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

      <SprintPanel organizationId={organizationId} projectId={projectId} />

      {board.isLoading && (
        <div className="grid gap-4 md:grid-cols-3 xl:grid-cols-6">
          {TASK_STATUSES.map((status) => (
            <Skeleton key={status} className="h-64" />
          ))}
        </div>
      )}

      {board.isError && (
        <ErrorState message={describeApiError(board.error)} onRetry={() => board.refetch()} />
      )}

      {board.isSuccess && (
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-6">
          {TASK_STATUSES.map((status) => {
            const column = board.data.find((entry) => entry.status === status);
            const tasks = column?.tasks ?? [];

            return (
              <section
                key={status}
                aria-label={STATUS_LABELS[status]}
                onDragOver={(event) => {
                  // Without preventDefault the browser refuses the drop.
                  event.preventDefault();
                  setDragOverColumn(status);
                }}
                onDragLeave={() => setDragOverColumn(null)}
                onDrop={(event) => handleDrop(event, status)}
                className={cn(
                  'rounded-2xl border bg-slate-900/40 p-3 transition-colors',
                  dragOverColumn === status
                    ? 'border-indigo-500/60 bg-indigo-500/5'
                    : 'border-slate-800',
                )}
                data-testid={`column-${status}`}
              >
                <header className="mb-3 flex items-center justify-between px-1">
                  <h2 className="text-sm font-semibold text-slate-300">{STATUS_LABELS[status]}</h2>
                  <span className="rounded-full bg-slate-800 px-2 py-0.5 text-xs text-slate-400">
                    {tasks.length}
                  </span>
                </header>

                <div className="space-y-2">
                  {tasks.map((task) => (
                    <TaskCard
                      key={task.id}
                      task={task}
                      projectKey={projectKey}
                      isDragging={dragged?.id === task.id}
                      onOpen={setOpenTask}
                      onMoveTo={(movedTask, newStatus) => {
                        if (movedTask.status !== newStatus) {
                          moveTask.mutate({ task: movedTask, status: newStatus });
                        }
                      }}
                      onDragStart={setDragged}
                      onDragEnd={() => setDragged(null)}
                    />
                  ))}

                  {tasks.length === 0 && (
                    <p className="px-1 py-6 text-center text-xs text-slate-400">Nothing here yet</p>
                  )}
                </div>
              </section>
            );
          })}
        </div>
      )}

      {shownTask && (
        <TaskDetailDrawer
          organizationId={organizationId}
          projectId={projectId}
          projectKey={projectKey}
          // Re-read from the freshly fetched board so the drawer does not show a
          // stale copy after a label or comment changes.
          task={
            board.data?.flatMap((column) => column.tasks).find((t) => t.id === shownTask.id) ??
            shownTask
          }
          onClose={() => setOpenTask(null)}
        />
      )}
    </div>
  );
}
