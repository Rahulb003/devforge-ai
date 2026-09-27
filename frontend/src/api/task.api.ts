import type { ApiEnvelope } from './auth.api';
import type { Page } from './project.api';

import api from '@/lib/axios';

export type TaskStatus = 'BACKLOG' | 'TODO' | 'IN_PROGRESS' | 'IN_REVIEW' | 'DONE' | 'BLOCKED';
export type TaskPriority = 'LOWEST' | 'LOW' | 'MEDIUM' | 'HIGH' | 'HIGHEST';
export type TaskType = 'TASK' | 'BUG' | 'STORY' | 'EPIC';
export type SprintStatus = 'PLANNED' | 'ACTIVE' | 'COMPLETED';

/** Column order on the board. The server returns every status, including empty ones. */
export const TASK_STATUSES: TaskStatus[] = [
  'BACKLOG',
  'TODO',
  'IN_PROGRESS',
  'IN_REVIEW',
  'BLOCKED',
  'DONE',
];

export const STATUS_LABELS: Record<TaskStatus, string> = {
  BACKLOG: 'Backlog',
  TODO: 'To do',
  IN_PROGRESS: 'In progress',
  IN_REVIEW: 'In review',
  BLOCKED: 'Blocked',
  DONE: 'Done',
};

export const PRIORITY_LABELS: Record<TaskPriority, string> = {
  LOWEST: 'Lowest',
  LOW: 'Low',
  MEDIUM: 'Medium',
  HIGH: 'High',
  HIGHEST: 'Highest',
};

export interface Task {
  id: string;
  projectId: string;
  organizationId: string;
  taskNumber: number;
  title: string;
  description: string | null;
  status: TaskStatus;
  priority: TaskPriority;
  type: TaskType;
  assigneeId: string | null;
  reporterId: string;
  storyPoints: number | null;
  dueDate: string | null;
  parentTaskId: string | null;
  sprintId: string | null;
  boardPosition: number;
  labels: string[];
  completedAt: string | null;
  createdAt: string;
  updatedAt: string | null;
}

/** One Kanban column, cards already in board order. */
export interface BoardColumn {
  status: TaskStatus;
  tasks: Task[];
}

export interface TaskComment {
  id: string;
  taskId: string;
  authorId: string;
  body: string;
  createdAt: string;
  updatedAt: string | null;
}

export interface Sprint {
  id: string;
  projectId: string;
  name: string;
  goal: string | null;
  status: SprintStatus;
  startDate: string | null;
  endDate: string | null;
  totalTasks: number;
  completedTasks: number;
  createdAt: string;
}

export interface CreateTaskData {
  title: string;
  description?: string;
  priority?: TaskPriority;
  type?: TaskType;
  storyPoints?: number;
  dueDate?: string;
  parentTaskId?: string;
  sprintId?: string;
}

export interface UpdateTaskData {
  title?: string;
  description?: string;
  priority?: TaskPriority;
  type?: TaskType;
  storyPoints?: number;
  dueDate?: string;
  sprintId?: string;
}

/**
 * Tasks are nested under their project but served by task-service.
 *
 * The dev proxy routes these paths to port 9003 while the surrounding project
 * routes go to 9002; in a deployed environment the gateway does that instead.
 */
function base(organizationId: string, projectId: string) {
  return `/organizations/${organizationId}/projects/${projectId}`;
}

export const taskApi = {
  board: (organizationId: string, projectId: string) =>
    api.get<ApiEnvelope<BoardColumn[]>>(`${base(organizationId, projectId)}/tasks/board`),

  list: (organizationId: string, projectId: string, page = 0, size = 50) =>
    api.get<ApiEnvelope<Page<Task>>>(
      `${base(organizationId, projectId)}/tasks?page=${page}&size=${size}`,
    ),

  get: (organizationId: string, projectId: string, taskId: string) =>
    api.get<ApiEnvelope<Task>>(`${base(organizationId, projectId)}/tasks/${taskId}`),

  create: (organizationId: string, projectId: string, data: CreateTaskData) =>
    api.post<ApiEnvelope<Task>>(`${base(organizationId, projectId)}/tasks`, data),

  update: (organizationId: string, projectId: string, taskId: string, data: UpdateTaskData) =>
    api.patch<ApiEnvelope<Task>>(`${base(organizationId, projectId)}/tasks/${taskId}`, data),

  /** Moves a card. A null position appends to the end of the column. */
  move: (
    organizationId: string,
    projectId: string,
    taskId: string,
    status: TaskStatus,
    position?: number,
  ) =>
    api.post<ApiEnvelope<Task>>(`${base(organizationId, projectId)}/tasks/${taskId}/move`, {
      status,
      position,
    }),

  assign: (organizationId: string, projectId: string, taskId: string, assigneeId: string | null) =>
    api.post<ApiEnvelope<Task>>(`${base(organizationId, projectId)}/tasks/${taskId}/assign`, {
      assigneeId,
    }),

  remove: (organizationId: string, projectId: string, taskId: string) =>
    api.delete<ApiEnvelope<void>>(`${base(organizationId, projectId)}/tasks/${taskId}`),

  addLabel: (organizationId: string, projectId: string, taskId: string, label: string) =>
    api.post<ApiEnvelope<Task>>(
      `${base(organizationId, projectId)}/tasks/${taskId}/labels?label=${encodeURIComponent(label)}`,
    ),

  removeLabel: (organizationId: string, projectId: string, taskId: string, label: string) =>
    api.delete<ApiEnvelope<Task>>(
      `${base(organizationId, projectId)}/tasks/${taskId}/labels?label=${encodeURIComponent(label)}`,
    ),

  comments: (organizationId: string, projectId: string, taskId: string) =>
    api.get<ApiEnvelope<TaskComment[]>>(
      `${base(organizationId, projectId)}/tasks/${taskId}/comments`,
    ),

  addComment: (organizationId: string, projectId: string, taskId: string, body: string) =>
    api.post<ApiEnvelope<TaskComment>>(
      `${base(organizationId, projectId)}/tasks/${taskId}/comments`,
      { body },
    ),

  deleteComment: (organizationId: string, projectId: string, taskId: string, commentId: string) =>
    api.delete<ApiEnvelope<void>>(
      `${base(organizationId, projectId)}/tasks/${taskId}/comments/${commentId}`,
    ),
};

export const sprintApi = {
  list: (organizationId: string, projectId: string) =>
    api.get<ApiEnvelope<Sprint[]>>(`${base(organizationId, projectId)}/sprints`),

  create: (
    organizationId: string,
    projectId: string,
    data: { name: string; goal?: string; startDate?: string; endDate?: string },
  ) => api.post<ApiEnvelope<Sprint>>(`${base(organizationId, projectId)}/sprints`, data),

  start: (organizationId: string, projectId: string, sprintId: string) =>
    api.post<ApiEnvelope<Sprint>>(`${base(organizationId, projectId)}/sprints/${sprintId}/start`),

  complete: (organizationId: string, projectId: string, sprintId: string) =>
    api.post<ApiEnvelope<Sprint>>(
      `${base(organizationId, projectId)}/sprints/${sprintId}/complete`,
    ),
};
