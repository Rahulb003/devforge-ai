import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Building2, Plus } from 'lucide-react';
import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';

import { projectApi } from '@/api/project.api';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { Input } from '@/components/ui/Input';
import { EmptyState, ErrorState, Skeleton } from '@/components/ui/states';
import { describeApiError } from '@/lib/errors';

/** Derives a URL-safe slug from a display name, matching the server's pattern. */
function toSlug(name: string): string {
  return name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 100);
}

export function OrganizationsPage() {
  const queryClient = useQueryClient();
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [slug, setSlug] = useState('');
  const [slugEdited, setSlugEdited] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const organizations = useQuery({
    queryKey: ['organizations'],
    queryFn: async () => (await projectApi.listOrganizations()).data.data,
  });

  const createOrganization = useMutation({
    mutationFn: (payload: { name: string; slug: string }) =>
      projectApi.createOrganization(payload).then((r) => r.data.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['organizations'] });
      setCreating(false);
      setName('');
      setSlug('');
      setSlugEdited(false);
      setFormError(null);
    },
    onError: (err) => setFormError(describeApiError(err)),
  });

  function handleNameChange(value: string) {
    setName(value);
    // Keep the slug in step until the user takes it over, then stop fighting them.
    if (!slugEdited) setSlug(toSlug(value));
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setFormError(null);
    createOrganization.mutate({ name, slug });
  }

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-white">Organizations</h1>
          <p className="mt-1 text-sm text-slate-400">
            Every project belongs to an organization. You only ever see the ones you are a member
            of.
          </p>
        </div>
        {!creating && (
          <Button leftIcon={<Plus className="h-4 w-4" />} onClick={() => setCreating(true)}>
            New organization
          </Button>
        )}
      </header>

      {creating && (
        <Card>
          <form onSubmit={handleSubmit} className="space-y-4" noValidate>
            <h2 className="text-lg font-semibold text-white">Create an organization</h2>

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
              label="Slug"
              value={slug}
              onChange={(e) => {
                setSlugEdited(true);
                setSlug(e.target.value);
              }}
              hint="Lowercase words separated by single hyphens. Must be unique."
              required
            />

            <div className="flex gap-3">
              <Button type="submit" loading={createOrganization.isPending}>
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

      {organizations.isLoading && (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {[0, 1, 2].map((i) => (
            <Skeleton key={i} className="h-32" />
          ))}
        </div>
      )}

      {organizations.isError && (
        <ErrorState
          message={describeApiError(organizations.error)}
          onRetry={() => organizations.refetch()}
        />
      )}

      {organizations.isSuccess && organizations.data.length === 0 && !creating && (
        <EmptyState
          icon={<Building2 className="h-6 w-6" />}
          title="No organizations yet"
          description="An organization is the tenant boundary: it owns your projects and decides who can see them. Create one to get started."
          action={
            <Button leftIcon={<Plus className="h-4 w-4" />} onClick={() => setCreating(true)}>
              Create organization
            </Button>
          }
        />
      )}

      {organizations.isSuccess && organizations.data.length > 0 && (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {organizations.data.map((organization) => (
            <Link
              key={organization.id}
              to={`/organizations/${organization.id}`}
              className="rounded-2xl focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-400"
            >
              <Card className="h-full transition-colors hover:border-slate-700">
                <div className="flex items-start justify-between gap-3">
                  <div className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-indigo-500/10 text-indigo-400">
                    <Building2 className="h-5 w-5" aria-hidden="true" />
                  </div>
                  <Badge tone={organization.role === 'OWNER' ? 'info' : 'neutral'}>
                    {organization.role}
                  </Badge>
                </div>
                <h2 className="mt-4 font-semibold text-white">{organization.name}</h2>
                <p className="mt-1 text-sm text-slate-500">/{organization.slug}</p>
                {organization.description && (
                  <p className="mt-2 line-clamp-2 text-sm text-slate-400">
                    {organization.description}
                  </p>
                )}
              </Card>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
