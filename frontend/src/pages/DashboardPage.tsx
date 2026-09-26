import { useQuery } from '@tanstack/react-query';
import { Building2, FolderGit2, ShieldCheck } from 'lucide-react';
import { Link } from 'react-router-dom';

import { projectApi } from '@/api/project.api';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { EmptyState, Skeleton } from '@/components/ui/states';
import { useAuthStore } from '@/stores/authStore';

/**
 * Dashboard.
 *
 * Every number here comes from a real request. The previous version displayed
 * a hard-coded "Healthy" tile and an invented status, which looks like working
 * software while reporting nothing.
 */
export function DashboardPage() {
  const user = useAuthStore((s) => s.user);

  const organizations = useQuery({
    queryKey: ['organizations'],
    queryFn: async () => (await projectApi.listOrganizations()).data.data,
  });

  return (
    <div className="space-y-6">
      <section className="rounded-3xl border border-slate-800 bg-slate-900 p-8 shadow-lg">
        <h1 className="text-3xl font-semibold text-white">
          Welcome back{user ? `, ${user.firstName}` : ''}
        </h1>
        <p className="mt-3 max-w-2xl text-slate-400">
          Build. Review. Test. Deploy. Collaborate. Authentication, organizations and projects are
          live; the remaining workspace features are still being built.
        </p>
      </section>

      <section className="grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
        <Card>
          <div className="flex items-center gap-3">
            <div className="grid h-10 w-10 place-items-center rounded-xl bg-indigo-500/10 text-indigo-400">
              <Building2 className="h-5 w-5" aria-hidden="true" />
            </div>
            <p className="text-sm text-slate-400">Organizations</p>
          </div>
          {organizations.isLoading ? (
            <Skeleton className="mt-4 h-10 w-16" />
          ) : (
            <p className="mt-4 text-4xl font-semibold text-white">
              {organizations.data?.length ?? 0}
            </p>
          )}
        </Card>

        <Card>
          <div className="flex items-center gap-3">
            <div className="grid h-10 w-10 place-items-center rounded-xl bg-emerald-500/10 text-emerald-400">
              <ShieldCheck className="h-5 w-5" aria-hidden="true" />
            </div>
            <p className="text-sm text-slate-400">Account</p>
          </div>
          <p className="mt-4 text-lg font-semibold text-white">{user?.status ?? '—'}</p>
          <p className="mt-1 text-sm text-slate-500">
            {user?.emailVerified ? 'Email verified' : 'Email not verified'}
          </p>
        </Card>

        <Card>
          <div className="flex items-center gap-3">
            <div className="grid h-10 w-10 place-items-center rounded-xl bg-slate-800 text-slate-400">
              <FolderGit2 className="h-5 w-5" aria-hidden="true" />
            </div>
            <p className="text-sm text-slate-400">Roles</p>
          </div>
          <p className="mt-4 text-lg font-semibold text-white">{user?.roles?.join(', ') ?? '—'}</p>
        </Card>
      </section>

      {organizations.isSuccess && organizations.data.length === 0 && (
        <EmptyState
          icon={<Building2 className="h-6 w-6" />}
          title="Nothing here yet"
          description="Create an organization to hold your projects. It is the boundary that decides who can see what."
          action={
            <Link to="/organizations">
              <Button>Go to organizations</Button>
            </Link>
          }
        />
      )}
    </div>
  );
}
