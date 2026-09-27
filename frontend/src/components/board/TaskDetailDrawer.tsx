import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Send, Trash2, X } from 'lucide-react';
import { useEffect, useRef, useState, type FormEvent } from 'react';

import {
  PRIORITY_LABELS,
  STATUS_LABELS,
  taskApi,
  type Task,
  type TaskPriority,
} from '@/api/task.api';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { LoadingState } from '@/components/ui/states';
import { describeApiError } from '@/lib/errors';
import { useAuthStore } from '@/stores/authStore';

interface TaskDetailDrawerProps {
  organizationId: string;
  projectId: string;
  projectKey: string;
  task: Task;
  onClose: () => void;
}

/**
 * Side panel for one task: details, labels and comments.
 *
 * Implemented as a modal dialog rather than a separate route, because the board
 * behind it stays meaningful context. That obliges it to behave like one:
 * Escape closes it, focus moves into it on open, and it is labelled so a screen
 * reader announces what opened.
 */
export function TaskDetailDrawer({
  organizationId,
  projectId,
  projectKey,
  task,
  onClose,
}: TaskDetailDrawerProps) {
  const queryClient = useQueryClient();
  const currentUser = useAuthStore((s) => s.user);
  const panelRef = useRef<HTMLDivElement>(null);

  const [comment, setComment] = useState('');
  const [newLabel, setNewLabel] = useState('');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    // Focus the panel so the next Tab lands inside it rather than back on the
    // board behind.
    panelRef.current?.focus();

    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') onClose();
    }
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  const comments = useQuery({
    queryKey: ['task-comments', task.id],
    queryFn: async () => (await taskApi.comments(organizationId, projectId, task.id)).data.data,
  });

  function invalidate() {
    queryClient.invalidateQueries({ queryKey: ['board', projectId] });
    queryClient.invalidateQueries({ queryKey: ['task-comments', task.id] });
  }

  const addComment = useMutation({
    mutationFn: (body: string) => taskApi.addComment(organizationId, projectId, task.id, body),
    onSuccess: () => {
      setComment('');
      setError(null);
      invalidate();
    },
    onError: (err) => setError(describeApiError(err)),
  });

  const deleteComment = useMutation({
    mutationFn: (commentId: string) =>
      taskApi.deleteComment(organizationId, projectId, task.id, commentId),
    onSuccess: () => {
      setError(null);
      invalidate();
    },
    onError: (err) => setError(describeApiError(err)),
  });

  const addLabel = useMutation({
    mutationFn: (label: string) => taskApi.addLabel(organizationId, projectId, task.id, label),
    onSuccess: () => {
      setNewLabel('');
      setError(null);
      invalidate();
    },
    onError: (err) => setError(describeApiError(err)),
  });

  const removeLabel = useMutation({
    mutationFn: (label: string) => taskApi.removeLabel(organizationId, projectId, task.id, label),
    onSuccess: () => invalidate(),
    onError: (err) => setError(describeApiError(err)),
  });

  const updateTask = useMutation({
    mutationFn: (priority: TaskPriority) =>
      taskApi.update(organizationId, projectId, task.id, { priority }),
    onSuccess: () => invalidate(),
    onError: (err) => setError(describeApiError(err)),
  });

  const removeTask = useMutation({
    mutationFn: () => taskApi.remove(organizationId, projectId, task.id),
    onSuccess: () => {
      invalidate();
      onClose();
    },
    onError: (err) => setError(describeApiError(err)),
  });

  return (
    <div className="fixed inset-0 z-50 flex justify-end">
      {/* Clicking away closes, which is what a drawer is expected to do. It is
          a plain div with aria-hidden: the same action is on a real button in
          the header, so this needs no keyboard handler of its own. */}
      <div className="absolute inset-0 bg-black/60" onClick={onClose} aria-hidden="true" />

      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-label={`Task ${projectKey}-${task.taskNumber}: ${task.title}`}
        tabIndex={-1}
        className="relative flex h-full w-full max-w-lg flex-col overflow-y-auto border-l border-slate-800 bg-slate-950 shadow-2xl focus:outline-none"
      >
        <header className="flex items-start justify-between gap-4 border-b border-slate-800 p-6">
          <div className="min-w-0">
            <p className="font-mono text-xs text-slate-500">
              {projectKey}-{task.taskNumber}
            </p>
            <h2 className="mt-1 text-lg font-semibold text-white">{task.title}</h2>
            <div className="mt-2 flex flex-wrap gap-2">
              <Badge tone="info">{STATUS_LABELS[task.status]}</Badge>
              <Badge>{task.type}</Badge>
              <Badge>{PRIORITY_LABELS[task.priority]}</Badge>
            </div>
          </div>
          <Button variant="ghost" size="sm" onClick={onClose} aria-label="Close task details">
            <X className="h-5 w-5" aria-hidden="true" />
          </Button>
        </header>

        {error && (
          <div
            role="alert"
            className="mx-6 mt-4 rounded-xl border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-300"
          >
            {error}
          </div>
        )}

        <div className="flex-1 space-y-6 p-6">
          <section>
            <h3 className="text-sm font-medium text-slate-300">Description</h3>
            <p className="mt-2 whitespace-pre-wrap text-sm text-slate-400">
              {task.description || 'No description yet.'}
            </p>
          </section>

          <section>
            <h3 className="text-sm font-medium text-slate-300">Priority</h3>
            <label className="sr-only" htmlFor="task-priority">
              Priority for {task.title}
            </label>
            <select
              id="task-priority"
              value={task.priority}
              onChange={(event) => updateTask.mutate(event.target.value as TaskPriority)}
              className="mt-2 rounded-lg border border-slate-700 bg-slate-900 px-3 py-2 text-sm text-slate-200 focus:outline focus:outline-2 focus:outline-indigo-400"
            >
              {(Object.keys(PRIORITY_LABELS) as TaskPriority[]).map((priority) => (
                <option key={priority} value={priority}>
                  {PRIORITY_LABELS[priority]}
                </option>
              ))}
            </select>
          </section>

          <section>
            <h3 className="text-sm font-medium text-slate-300">Labels</h3>
            {task.labels.length > 0 ? (
              <ul className="mt-2 flex flex-wrap gap-2">
                {task.labels.map((label) => (
                  <li key={label} className="flex items-center gap-1">
                    <Badge>{label}</Badge>
                    <button
                      type="button"
                      onClick={() => removeLabel.mutate(label)}
                      aria-label={`Remove label ${label}`}
                      className="rounded p-0.5 text-slate-500 hover:text-red-400 focus-visible:outline focus-visible:outline-2 focus-visible:outline-indigo-400"
                    >
                      <X className="h-3 w-3" aria-hidden="true" />
                    </button>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="mt-2 text-sm text-slate-500">No labels.</p>
            )}

            <form
              onSubmit={(event: FormEvent) => {
                event.preventDefault();
                if (newLabel.trim()) addLabel.mutate(newLabel.trim());
              }}
              className="mt-3 flex gap-2"
            >
              <div className="flex-1">
                <Input
                  label="Add a label"
                  value={newLabel}
                  onChange={(event) => setNewLabel(event.target.value)}
                  placeholder="backend"
                />
              </div>
              <Button
                type="submit"
                variant="secondary"
                className="mt-7"
                loading={addLabel.isPending}
              >
                Add
              </Button>
            </form>
          </section>

          <section>
            <h3 className="text-sm font-medium text-slate-300">Comments</h3>

            {comments.isLoading && <LoadingState label="Loading comments…" />}

            {comments.isSuccess && comments.data.length === 0 && (
              <p className="mt-2 text-sm text-slate-500">No comments yet.</p>
            )}

            {comments.isSuccess && comments.data.length > 0 && (
              <ul className="mt-3 space-y-3">
                {comments.data.map((entry) => (
                  <li
                    key={entry.id}
                    className="rounded-xl border border-slate-800 bg-slate-900 px-4 py-3"
                  >
                    <div className="flex items-start justify-between gap-3">
                      <p className="whitespace-pre-wrap text-sm text-slate-300">{entry.body}</p>
                      {/* Only the author may delete; the server enforces it, and
                          showing the control to everyone would invite a 403. */}
                      {currentUser?.id === entry.authorId && (
                        <button
                          type="button"
                          onClick={() => deleteComment.mutate(entry.id)}
                          aria-label="Delete comment"
                          className="rounded p-1 text-slate-500 hover:text-red-400 focus-visible:outline focus-visible:outline-2 focus-visible:outline-indigo-400"
                        >
                          <Trash2 className="h-4 w-4" aria-hidden="true" />
                        </button>
                      )}
                    </div>
                    <p className="mt-1 text-xs text-slate-600">
                      {new Date(entry.createdAt).toLocaleString()}
                    </p>
                  </li>
                ))}
              </ul>
            )}

            <form
              onSubmit={(event: FormEvent) => {
                event.preventDefault();
                if (comment.trim()) addComment.mutate(comment.trim());
              }}
              className="mt-4 flex gap-2"
            >
              <div className="flex-1">
                <Input
                  label="Add a comment"
                  value={comment}
                  onChange={(event) => setComment(event.target.value)}
                  placeholder="Share an update…"
                />
              </div>
              <Button
                type="submit"
                className="mt-7"
                loading={addComment.isPending}
                leftIcon={<Send className="h-4 w-4" />}
              >
                Post
              </Button>
            </form>
          </section>
        </div>

        <footer className="border-t border-slate-800 p-6">
          <Button
            variant="danger"
            leftIcon={<Trash2 className="h-4 w-4" />}
            loading={removeTask.isPending}
            onClick={() => removeTask.mutate()}
          >
            Delete task
          </Button>
        </footer>
      </div>
    </div>
  );
}
