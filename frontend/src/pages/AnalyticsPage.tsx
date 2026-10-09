import { useQuery } from '@tanstack/react-query';
import { ArrowLeft } from 'lucide-react';
import { Link, useParams } from 'react-router-dom';

import { analyticsApi } from '@/api/chat.api';
import { Card } from '@/components/ui/Card';
import { ErrorState, LoadingState } from '@/components/ui/states';
import { describeApiError } from '@/lib/errors';

/**
 * Last 30 days of project activity, counted from domain events.
 *
 * Every number comes from the server; none is computed or invented here. The completeness note the
 * server returns is shown verbatim, because "zero" can mean a quiet project or no event pipeline.
 */
export function AnalyticsPage() {
  const { organizationId = '', projectId = '' } = useParams();

  const activity = useQuery({
    queryKey: ['analytics', projectId],
    queryFn: async () => (await analyticsApi.activity(organizationId, projectId)).data.data,
    enabled: Boolean(organizationId && projectId),
  });

  const data = activity.data;
  const peak = data ? Math.max(1, ...data.days.map((d) => d.tasksCreated + d.commits)) : 1;

  return (
    <div className="space-y-6">
      <Link
        to={`/organizations/${organizationId}/projects/${projectId}`}
        className="inline-flex items-center gap-2 text-sm text-slate-400 underline-offset-4 hover:text-slate-200 hover:underline"
      >
        <ArrowLeft className="h-4 w-4" aria-hidden="true" />
        Back to board
      </Link>

      <h1 className="text-2xl font-semibold text-white">Project activity</h1>

      {activity.isLoading && <LoadingState label="Loading activity…" />}
      {activity.isError && (
        <ErrorState
          message={describeApiError(activity.error)}
          onRetry={() => void activity.refetch()}
        />
      )}

      {data && (
        <>
          <div className="grid gap-4 sm:grid-cols-4">
            {[
              ['Tasks created', data.totalTasksCreated],
              ['Tasks completed', data.totalTasksCompleted],
              ['Tasks assigned', data.totalTasksAssigned],
              ['Commits', data.totalCommits],
            ].map(([label, value]) => (
              <Card key={label}>
                <p className="text-sm text-slate-400">{label}</p>
                <p className="mt-2 text-3xl font-semibold text-white">{value}</p>
              </Card>
            ))}
          </div>

          <Card>
            <h2 className="text-sm font-medium text-slate-300">
              Daily activity, {data.from} to {data.to}
            </h2>
            {/* A labelled bar per day; the table below carries the same numbers for screen readers. */}
            <div className="mt-4 flex h-32 items-end gap-1" aria-hidden="true">
              {data.days.map((d) => (
                <div
                  key={d.day}
                  title={`${d.day}: ${d.tasksCreated} tasks, ${d.commits} commits`}
                  className="flex-1 rounded-t bg-indigo-500/60"
                  style={{ height: `${((d.tasksCreated + d.commits) / peak) * 100}%` }}
                />
              ))}
            </div>
            <table className="sr-only">
              <caption>Daily activity</caption>
              <thead>
                <tr>
                  <th>Day</th>
                  <th>Tasks created</th>
                  <th>Commits</th>
                </tr>
              </thead>
              <tbody>
                {data.days.map((d) => (
                  <tr key={d.day}>
                    <td>{d.day}</td>
                    <td>{d.tasksCreated}</td>
                    <td>{d.commits}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>

          <p className="text-xs text-slate-400">{data.completeness}</p>
        </>
      )}

      <AuditLog organizationId={organizationId} projectId={projectId} />
    </div>
  );
}

/** Event types in plain words; anything new shows its raw name rather than nothing. */
const AUDIT_LABELS: Record<string, string> = {
  ProjectCreated: 'Project created',
  ProjectUpdated: 'Project updated',
  ProjectArchived: 'Project archived',
  ProjectDeleted: 'Project deleted',
  ProjectMemberAdded: 'Member added',
  ProjectMemberRemoved: 'Member removed',
  TaskCreated: 'Task created',
  TaskUpdated: 'Task updated',
  TaskAssigned: 'Task assigned',
  TaskCompleted: 'Task completed',
  TaskDeleted: 'Task deleted',
  RepositoryCreated: 'Repository created',
  RepositoryDeleted: 'Repository deleted',
  RepositoryPushed: 'Commit',
  PullRequestOpened: 'Pull request opened',
  PullRequestMerged: 'Pull request merged',
  PullRequestClosed: 'Pull request closed',
};

/** The few details worth a glance; the rest are ids. */
function summarise(details: Record<string, unknown>): string {
  const parts: string[] = [];
  for (const key of ['name', 'title', 'role', 'branch', 'number']) {
    const value = details[key];
    if (value !== undefined && value !== null && value !== '') {
      parts.push(key === 'number' ? `#${String(value)}` : String(value));
    }
  }
  return parts.join(' · ');
}

/**
 * Who changed what in this project, newest first. Visible to project admins; for anyone else the
 * server answers 403 and this says so, rather than showing an empty log that implies nothing
 * happened.
 */
function AuditLog({ organizationId, projectId }: { organizationId: string; projectId: string }) {
  const audit = useQuery({
    queryKey: ['audit', organizationId, projectId],
    queryFn: async () => (await analyticsApi.audit(organizationId, projectId)).data.data,
    retry: false,
  });
  const forbidden =
    audit.isError &&
    typeof audit.error === 'object' &&
    audit.error !== null &&
    'response' in audit.error &&
    (audit.error as { response?: { status?: number } }).response?.status === 403;

  return (
    <section aria-labelledby="audit-log" className="space-y-3">
      <h2 id="audit-log" className="text-lg font-semibold text-white">
        Audit log
      </h2>
      {audit.isLoading && <LoadingState label="Loading the audit log…" />}
      {forbidden && (
        <p className="text-sm text-slate-400">
          Only project admins and team leads can see the audit log.
        </p>
      )}
      {audit.isError && !forbidden && (
        <ErrorState message={describeApiError(audit.error)} onRetry={() => void audit.refetch()} />
      )}
      {audit.isSuccess && audit.data.content.length === 0 && (
        <p className="text-sm text-slate-400">Nothing recorded yet.</p>
      )}
      {audit.isSuccess && audit.data.content.length > 0 && (
        <Card className="overflow-x-auto p-0">
          <table className="w-full text-left text-sm" aria-label="Audit log">
            <thead className="text-xs text-slate-400">
              <tr>
                <th className="px-4 py-2 font-medium">When</th>
                <th className="px-4 py-2 font-medium">What</th>
                <th className="px-4 py-2 font-medium">Details</th>
                <th className="px-4 py-2 font-medium">Source</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-800">
              {audit.data.content.map((entry) => (
                <tr key={entry.eventId}>
                  <td className="px-4 py-2 whitespace-nowrap text-slate-400">
                    {new Date(entry.occurredAt).toLocaleString()}
                  </td>
                  <td className="px-4 py-2 text-slate-200">
                    {AUDIT_LABELS[entry.eventType] ?? entry.eventType}
                  </td>
                  <td className="px-4 py-2 text-slate-300">{summarise(entry.details)}</td>
                  <td className="px-4 py-2 text-xs text-slate-400">{entry.source}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}
    </section>
  );
}
