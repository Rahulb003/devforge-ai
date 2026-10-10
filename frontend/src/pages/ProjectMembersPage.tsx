import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, UserMinus, UserPlus } from 'lucide-react';
import { useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router-dom';

import { membershipApi } from '@/api/membership.api';
import { projectApi, type ProjectRole } from '@/api/project.api';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { EmptyState, ErrorState, Skeleton } from '@/components/ui/states';
import { describeApiError } from '@/lib/errors';

const ROLES: ProjectRole[] = ['ADMIN', 'TEAM_LEAD', 'DEVELOPER', 'TESTER', 'VIEWER'];
const ROLE_HINTS: Record<ProjectRole, string> = {
  ADMIN: 'manages the project',
  TEAM_LEAD: 'manages the project',
  DEVELOPER: 'writes code and tasks',
  TESTER: 'writes code and tasks',
  VIEWER: 'read only',
};

/**
 * Who works on a project. People are added from the organization's members; inviting someone new
 * happens on the organization page first, because project access is granted per organization member.
 */
export function ProjectMembersPage() {
  const { organizationId = '', projectId = '' } = useParams();
  const queryClient = useQueryClient();
  const [candidate, setCandidate] = useState('');
  const [role, setRole] = useState<ProjectRole>('DEVELOPER');
  const [error, setError] = useState<string | null>(null);

  const project = useQuery({
    queryKey: ['project', organizationId, projectId],
    queryFn: async () => (await projectApi.getProject(organizationId, projectId)).data.data,
  });
  const projectMembers = useQuery({
    queryKey: ['project-members', projectId],
    queryFn: async () => (await projectApi.listMembers(organizationId, projectId)).data.data,
  });
  const orgMembers = useQuery({
    queryKey: ['org-members', organizationId],
    queryFn: async () => (await membershipApi.members(organizationId)).data.data,
  });

  const canManage = project.data?.role === 'ADMIN' || project.data?.role === 'TEAM_LEAD';
  const nameOf = (userId: string) => {
    const member = orgMembers.data?.find((m) => m.userId === userId);
    return member?.username ?? member?.email ?? userId.slice(0, 8);
  };
  const inProject = new Set(projectMembers.data?.map((m) => m.userId));
  const candidates = (orgMembers.data ?? []).filter((m) => !inProject.has(m.userId));

  const refresh = () => {
    setError(null);
    void queryClient.invalidateQueries({ queryKey: ['project-members', projectId] });
  };
  const add = useMutation({
    mutationFn: () => projectApi.addMember(organizationId, projectId, candidate, role),
    onSuccess: () => {
      setCandidate('');
      refresh();
    },
    onError: (err) => setError(describeApiError(err)),
  });
  const remove = useMutation({
    mutationFn: (userId: string) => projectApi.removeMember(organizationId, projectId, userId),
    onSuccess: refresh,
    onError: (err) => setError(describeApiError(err)),
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    if (candidate) add.mutate();
  }

  return (
    <div className="space-y-6">
      <Link
        to={`/organizations/${organizationId}/projects/${projectId}`}
        className="inline-flex items-center gap-2 text-sm text-slate-400 underline-offset-4 hover:text-slate-200 hover:underline"
      >
        <ArrowLeft className="h-4 w-4" aria-hidden="true" />
        Back to the board
      </Link>
      <header>
        <h1 className="text-2xl font-semibold text-white">Project members</h1>
        <p className="mt-1 text-sm text-slate-400">
          {project.data?.name ?? 'This project'}. Organization owners and admins can manage every
          project without being listed here.
        </p>
      </header>

      {error && (
        <p role="alert" className="text-sm text-red-400">
          {error}
        </p>
      )}

      {canManage && (
        <Card>
          <form
            onSubmit={submit}
            aria-label="Add a member"
            className="flex flex-wrap items-end gap-3"
          >
            <label className="min-w-56 flex-1 text-sm text-slate-300">
              <span className="mb-1.5 block font-medium">Organization member</span>
              <select
                value={candidate}
                onChange={(e) => setCandidate(e.target.value)}
                className="h-11 w-full rounded-xl border border-slate-700 bg-slate-950 px-3 text-slate-100"
              >
                <option value="">
                  {candidates.length === 0 ? 'Everyone is already here' : 'Choose someone'}
                </option>
                {candidates.map((m) => (
                  <option key={m.userId} value={m.userId}>
                    {m.username ?? m.email ?? m.userId.slice(0, 8)}
                  </option>
                ))}
              </select>
            </label>
            <label className="text-sm text-slate-300">
              <span className="mb-1.5 block font-medium">Role</span>
              <select
                value={role}
                onChange={(e) => setRole(e.target.value as ProjectRole)}
                className="h-11 rounded-xl border border-slate-700 bg-slate-950 px-3 text-slate-100"
              >
                {ROLES.map((r) => (
                  <option key={r} value={r}>
                    {r} - {ROLE_HINTS[r]}
                  </option>
                ))}
              </select>
            </label>
            <Button
              type="submit"
              loading={add.isPending}
              disabled={!candidate}
              leftIcon={<UserPlus className="h-4 w-4" />}
            >
              Add to project
            </Button>
          </form>
          <p className="mt-3 text-xs text-slate-400">
            Someone not listed? Invite them to the organization first, from{' '}
            <Link
              to={`/organizations/${organizationId}`}
              className="underline underline-offset-4 hover:text-slate-200"
            >
              its page
            </Link>
            .
          </p>
        </Card>
      )}

      {(projectMembers.isLoading || orgMembers.isLoading) && <Skeleton className="h-24" />}
      {projectMembers.isError && (
        <ErrorState
          message={describeApiError(projectMembers.error)}
          onRetry={() => projectMembers.refetch()}
        />
      )}
      {projectMembers.isSuccess && projectMembers.data.length === 0 && (
        <EmptyState
          icon={<UserPlus className="h-6 w-6" />}
          title="No one is listed on this project"
          description="Organization owners and admins have access anyway. Add members to give others access."
        />
      )}
      {projectMembers.isSuccess && projectMembers.data.length > 0 && (
        <ul aria-label="Project members" className="space-y-2">
          {projectMembers.data.map((member) => (
            <li
              key={member.userId}
              className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-slate-800 bg-slate-950 px-4 py-3"
            >
              <span className="font-medium text-slate-200">{nameOf(member.userId)}</span>
              <div className="flex items-center gap-2">
                <Badge>{member.role}</Badge>
                {canManage && (
                  <Button
                    variant="ghost"
                    size="sm"
                    leftIcon={<UserMinus className="h-4 w-4" />}
                    loading={remove.isPending && remove.variables === member.userId}
                    onClick={() => remove.mutate(member.userId)}
                    aria-label={`Remove ${nameOf(member.userId)} from the project`}
                  >
                    Remove
                  </Button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
