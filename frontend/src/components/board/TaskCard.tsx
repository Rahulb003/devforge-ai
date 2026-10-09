import { AlertTriangle, Bug, CheckCircle2, Layers, User } from 'lucide-react';
import type { DragEvent } from 'react';

import { STATUS_LABELS, TASK_STATUSES, type Task, type TaskStatus } from '@/api/task.api';
import { Badge } from '@/components/ui/Badge';
import { cn } from '@/lib/utils';

const typeIcons = {
  TASK: CheckCircle2,
  BUG: Bug,
  STORY: Layers,
  EPIC: Layers,
} as const;

const priorityTone = {
  LOWEST: 'neutral',
  LOW: 'neutral',
  MEDIUM: 'info',
  HIGH: 'warning',
  HIGHEST: 'danger',
} as const;

interface TaskCardProps {
  task: Task;
  projectKey: string;
  onOpen: (task: Task) => void;
  onMoveTo: (task: Task, status: TaskStatus) => void;
  onDragStart: (task: Task) => void;
  onDragEnd: () => void;
  isDragging: boolean;
}

/**
 * One card on the board.
 *
 * Draggable for a mouse, but drag-and-drop alone is unusable by keyboard and
 * screen-reader users, so every card also carries a status select that performs
 * the same move. That is the accessible path, not a fallback — it is always
 * present and does not depend on pointer events.
 */
export function TaskCard({
  task,
  projectKey,
  onOpen,
  onMoveTo,
  onDragStart,
  onDragEnd,
  isDragging,
}: TaskCardProps) {
  const TypeIcon = typeIcons[task.type] ?? CheckCircle2;

  function handleDragStart(event: DragEvent<HTMLDivElement>) {
    // Required by Firefox, which ignores a drag with no data payload.
    event.dataTransfer.setData('text/plain', task.id);
    event.dataTransfer.effectAllowed = 'move';
    onDragStart(task);
  }

  return (
    <div
      draggable
      onDragStart={handleDragStart}
      onDragEnd={onDragEnd}
      className={cn(
        'rounded-xl border border-slate-800 bg-slate-900 p-3 transition-colors',
        'hover:border-slate-700',
        isDragging && 'opacity-40',
      )}
      data-testid="task-card"
    >
      <div className="flex items-start justify-between gap-2">
        <button
          type="button"
          onClick={() => onOpen(task)}
          className="min-w-0 flex-1 text-left focus-visible:outline-solid focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-400"
        >
          <span className="flex items-center gap-2 text-xs font-mono text-slate-400">
            <TypeIcon className="h-3.5 w-3.5" aria-hidden="true" />
            {projectKey}-{task.taskNumber}
          </span>
          <span className="mt-1 block text-sm font-medium text-slate-100">{task.title}</span>
        </button>

        {task.priority === 'HIGHEST' && (
          <AlertTriangle className="h-4 w-4 shrink-0 text-red-400" aria-label="Highest priority" />
        )}
      </div>

      {task.labels.length > 0 && (
        <ul className="mt-2 flex flex-wrap gap-1">
          {task.labels.map((label) => (
            <li key={label}>
              <Badge>{label}</Badge>
            </li>
          ))}
        </ul>
      )}

      <div className="mt-3 flex items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <Badge tone={priorityTone[task.priority]}>{task.priority}</Badge>
          {task.storyPoints != null && <Badge>{task.storyPoints} pts</Badge>}
          {task.assigneeId && (
            <span
              className="grid h-6 w-6 place-items-center rounded-full bg-indigo-500/15 text-[10px] font-semibold text-indigo-300"
              title="Assigned"
            >
              <User className="h-3 w-3" aria-hidden="true" />
            </span>
          )}
        </div>

        {/*
          The keyboard-operable equivalent of dragging. Labelled per card so a
          screen reader announces which task is being moved, not just "status".
        */}
        <label className="sr-only" htmlFor={`move-${task.id}`}>
          Move {task.title} to another column
        </label>
        <select
          id={`move-${task.id}`}
          value={task.status}
          onChange={(event) => onMoveTo(task, event.target.value as TaskStatus)}
          className="rounded-lg border border-slate-700 bg-slate-800 px-2 py-1 text-xs text-slate-300 focus:outline-solid focus:outline-2 focus:outline-indigo-400"
        >
          {TASK_STATUSES.map((status) => (
            <option key={status} value={status}>
              {STATUS_LABELS[status]}
            </option>
          ))}
        </select>
      </div>
    </div>
  );
}
