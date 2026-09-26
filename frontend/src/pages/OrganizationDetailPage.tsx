import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, FolderGit2, Plus } from 'lucide-react';
import { useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router-dom';

import { projectApi } from '@/api/project.api';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { Input } from '@/components/ui/Input';
import { EmptyState, ErrorState, Skeleton } from '@/components/ui/states';
import { describeApiError } from '@/lib/errors';

/** Suggests a project key from the name: first letters, uppercase, max 10. */
function toProjectKey(name: string): string {
  const words = name.trim().split(/\s+/).filter(Boolean);
  const candidate =
    words.length > 1
      ? words.map((w) => w[0]).join('')
      : name.replace(/[^A-Za-z0-9]/g, '').slice(0, 6);
  return candidate
    .toUpperCase()
    .replace(/[^A-Z0-9]/g, '')
    .slice(0, 10);
}

export function OrganizationDetailPage() {
  const { organizationId = '' } = useParams();
  const queryClient = useQueryClient();

  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [projectKey, setProjectKey] = useState('');
  const [keyEdited, setKeyEdited] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const organization = useQuery({
    queryKey: ['organization', organizationId],
    queryFn: async () => (await projectApi.getOrganization(organizationId)).data.data,
  });

  const projects = useQuery({
    queryKey: ['projects', organizationId],
    queryFn: async () => (await projectApi.listProjects(organizationId)).data.data,
  });

  const createProject = useMutation({
    mutationFn: (payload: { name: string; projectKey: string }) =>
      projectApi.createProject(organizationId, payload).then((r) => r.data.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['projects', organizationId] });
      setCreating(false);
      setName('');
      setProjectKey('');
      setKeyEdited(false);
      setFormError(null);
    },
    onError: (err) => setFormError(describeApiError(err)),
  });

  function handleNameChange(value: string) {
    setName(value);
    if (!keyEdited) setProjectKey(toProjectKey(value));
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setFormError(null);
    createProject.mutate({ name, projectKey });
  }

  // A 404 here means either no such organization or one in another tenant. The
  // server deliberately does not distinguish those, and neither does this page.
  if (organization.isError) {
    return (
      <ErrorState
        title="Organization not found"
        message="It may not exist, or you may not be a member of it."
        onRetry={() => organization.refetch()}
      />
    );
  }

  return (
    <div className="space-y-6">
      <Link
        to="/organizations"
        className="inline-flex items-center gap-2 text-sm text-slate-400 underline-offset-4 hover:text-slate-200 hover:underline"
      >
        <ArrowLeft className="h-4 w-4" aria-hidden="true" />
        All organizations
      </Link>

      <header className="flex flex-wrap items-center justify-between gap-4">
        <div>
          {organization.isLoading ? (
            <Skeleton className="h-8 w-48" />
          ) : (
            <>
              <div className="flex items-center gap-3">
                <h1 className="text-2xl font-semibold text-white">{organization.data?.name}</h1>
                {organization.data && <Badge tone="info">{organization.data.role}</Badge>}
              </div>
              <p className="mt-1 text-sm text-slate-400">/{organization.data?.slug}</p>
            </>
          )}
        </div>
        {!creating && organization.data && organization.data.role !== 'MEMBER' && (
          <Button leftIcon={<Plus className="h-4 w-4" />} onClick={() => setCreating(true)}>
            New project
          </Button>
        )}
      </header>

      {creating && (
        <Card>
          <form onSubmit={handleSubmit} className="space-y-4" noValidate>
            <h2 className="text-lg font-semibold text-white">Create a project</h2>

            {formError && (
              <div
                role="alert"
                className="rounded-xl border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-300"
              >
                {formError}
              </div>
            )}

            <Input
              label="Name"
              value={name}
              onChange={(e) => handleNameChange(e.target.value)}
              required
            />
            <Input
              label="Project key"
              value={projectKey}
              onChange={(e) => {
                setKeyEdited(true);
                setProjectKey(e.target.value.toUpperCase());
              }}
              hint="Uppercase letters and digits, starting with a letter. Unique within this organization."
              required
            />

            <div className="flex gap-3">
              <Button type="submit" loading={createProject.isPending}>
                Create
              </Button>
              <Button
                type="button"
                variant="ghost"
                onClick={() => {
                  setCreating(false);
                  setFormError(null);
                }}
              >
                Cancel
              </Button>
            </div>
          </form>
        </Card>
      )}

      <section aria-labelledby="projects-heading" className="space-y-4">
        <h2 id="projects-heading" className="text-lg font-semibold text-white">
          Projects
        </h2>

        {projects.isLoading && (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {[0, 1, 2].map((i) => (
              <Skeleton key={i} className="h-32" />
            ))}
          </div>
        )}

        {projects.isError && (
          <ErrorState
            message={describeApiError(projects.error)}
            onRetry={() => projects.refetch()}
          />
        )}

        {projects.isSuccess && projects.data.content.length === 0 && !creating && (
          <EmptyState
            icon={<FolderGit2 className="h-6 w-6" />}
            title="No projects in this organization"
            description="A project groups a repository, its tasks and its deployments. Create one to start working."
            action={
              <Button leftIcon={<Plus className="h-4 w-4" />} onClick={() => setCreating(true)}>
                Create project
              </Button>
            }
          />
        )}

        {projects.isSuccess && projects.data.content.length > 0 && (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {projects.data.content.map((project) => (
              <Card key={project.id} className="h-full">
                <div className="flex items-start justify-between gap-3">
                  <div className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-slate-800 font-mono text-xs font-semibold text-indigo-300">
                    {project.projectKey.slice(0, 4)}
                  </div>
                  <div className="flex gap-2">
                    {project.role && <Badge>{project.role}</Badge>}
                    <Badge tone={project.status === 'ACTIVE' ? 'success' : 'warning'}>
                      {project.status}
                    </Badge>
                  </div>
                </div>
                <h3 className="mt-4 font-semibold text-white">{project.name}</h3>
                {project.description && (
                  <p className="mt-1 line-clamp-2 text-sm text-slate-400">{project.description}</p>
                )}
              </Card>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}
