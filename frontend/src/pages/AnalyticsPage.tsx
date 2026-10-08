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

          <p className="text-xs text-slate-500">{data.completeness}</p>
        </>
      )}
    </div>
  );
}
