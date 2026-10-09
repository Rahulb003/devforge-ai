import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, BookText } from 'lucide-react';
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';

import type { DocumentKind } from '@/api/docs.api';
import { DOCUMENT_KIND_LABELS, docsApi } from '@/api/docs.api';
import { gitApi } from '@/api/git.api';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { EmptyState, ErrorState, LoadingState } from '@/components/ui/states';
import { describeApiError } from '@/lib/errors';

export function DocsPage() {
  const { organizationId = '', projectId = '', repositoryId = '' } = useParams();
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<DocumentKind | null>(null);

  const repository = useQuery({
    queryKey: ['repository', organizationId, projectId, repositoryId],
    queryFn: async () => (await gitApi.get(organizationId, projectId, repositoryId)).data.data,
    enabled: Boolean(organizationId && projectId && repositoryId),
  });

  /**
   * The newest set.
   *
   * A 404 means "never generated", which is a normal state rather than a failure — so it is not
   * retried and not rendered as an error.
   */
  const latest = useQuery({
    queryKey: ['docs-latest', repositoryId],
    queryFn: async () => {
      try {
        return (await docsApi.latest(organizationId, projectId, repositoryId)).data.data;
      } catch (err) {
        if ((err as { response?: { status?: number } })?.response?.status === 404) return null;
        throw err;
      }
    },
    enabled: Boolean(repositoryId),
    retry: false,
  });

  const docSet = latest.data;

  const documents = useQuery({
    queryKey: ['docs-documents', docSet?.id],
    queryFn: async () =>
      (await docsApi.documents(organizationId, projectId, repositoryId, docSet!.id)).data.data,
    enabled: Boolean(docSet?.id) && docSet?.status === 'COMPLETED',
  });

  const current = useQuery({
    queryKey: ['docs-document', docSet?.id, selected],
    queryFn: async () =>
      (await docsApi.document(organizationId, projectId, repositoryId, docSet!.id, selected!)).data
        .data,
    enabled: Boolean(docSet?.id && selected),
  });

  const generate = useMutation({
    mutationFn: () => docsApi.generate(organizationId, projectId, repositoryId),
    onSuccess: () => {
      setError(null);
      setSelected(null);
      void queryClient.invalidateQueries({ queryKey: ['docs-latest'] });
      void queryClient.invalidateQueries({ queryKey: ['docs-documents'] });
    },
    // A 503 means the code could not be read. Saying so matters: the alternative reading is
    // "this repository has nothing to document".
    onError: (err) => setError(describeApiError(err)),
  });

  const available = documents.data ?? [];

  return (
    <div className="space-y-6">
      <Link
        to={`/organizations/${organizationId}/projects/${projectId}/repositories/${repositoryId}`}
        className="inline-flex items-center gap-2 text-sm text-slate-400 underline-offset-4 hover:text-slate-200 hover:underline"
      >
        <ArrowLeft className="h-4 w-4" aria-hidden="true" />
        Back to {repository.data?.name ?? 'repository'}
      </Link>

      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-white">Documentation</h1>
          <p className="mt-1 max-w-2xl text-sm text-slate-400">
            Generated from the committed code — languages and structure, the HTTP endpoints found in
            the source, and how much of the public surface is documented. Not AI-written.
          </p>
        </div>
        <Button onClick={() => generate.mutate()} loading={generate.isPending}>
          {docSet ? 'Regenerate' : 'Generate'}
        </Button>
      </header>

      {error && (
        <div
          role="alert"
          className="rounded-xl border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-300"
        >
          {error}
        </div>
      )}

      {latest.isLoading && <LoadingState label="Loading documentation…" />}

      {latest.isError && (
        <ErrorState
          message={describeApiError(latest.error)}
          onRetry={() => void latest.refetch()}
        />
      )}

      {latest.isSuccess && !docSet && (
        <EmptyState
          icon={<BookText className="h-6 w-6" aria-hidden="true" />}
          title="No documentation yet"
          description="Generate it to get an overview of this repository, its API surface and its documentation coverage."
        />
      )}

      {docSet?.status === 'FAILED' && (
        <div
          role="alert"
          className="rounded-2xl border border-amber-500/40 bg-amber-500/10 px-5 py-4 text-sm text-amber-200"
        >
          <p className="font-semibold">This documentation could not be generated.</p>
          <p className="mt-1">
            {docSet.failureReason ?? 'The repository content was unavailable.'}
          </p>
          <p className="mt-2 text-amber-300/80">
            Deliberately not shown as an empty result — nothing was read, so nothing can be said
            about this repository.
          </p>
        </div>
      )}

      {docSet?.status === 'COMPLETED' && (
        <>
          <p className="text-sm text-slate-400">
            Generated from <span className="font-mono text-slate-400">{docSet.ref}</span> ·{' '}
            {docSet.filesScanned} file{docSet.filesScanned === 1 ? '' : 's'} scanned
          </p>

          {documents.isLoading && <LoadingState label="Loading documents…" />}

          {documents.isError && (
            <ErrorState
              message={describeApiError(documents.error)}
              onRetry={() => void documents.refetch()}
            />
          )}

          {documents.isSuccess && available.length === 0 && (
            <EmptyState
              icon={<BookText className="h-6 w-6" aria-hidden="true" />}
              title="Nothing to document"
              description="The scan found no recognised source, API routes or public declarations in this repository."
            />
          )}

          {available.length > 0 && (
            <div className="flex gap-2 border-b border-slate-800" role="tablist">
              {available.map((document) => (
                <button
                  key={document.kind}
                  type="button"
                  role="tab"
                  aria-selected={selected === document.kind}
                  onClick={() => setSelected(document.kind)}
                  className={`-mb-px border-b-2 px-4 py-2 text-sm font-medium transition ${
                    selected === document.kind
                      ? 'border-indigo-400 text-white'
                      : 'border-transparent text-slate-400 hover:text-slate-200'
                  }`}
                >
                  {DOCUMENT_KIND_LABELS[document.kind] ?? document.kind}
                </button>
              ))}
            </div>
          )}

          {available.length > 0 && !selected && (
            <p className="text-sm text-slate-400">Choose a document above to read it.</p>
          )}

          {selected && current.isLoading && <LoadingState label="Loading document…" />}

          {selected && current.isError && (
            <ErrorState
              message={describeApiError(current.error)}
              onRetry={() => void current.refetch()}
            />
          )}

          {selected && current.data && (
            <Card>
              {/*
                Rendered as preformatted Markdown rather than parsed to HTML. The content is
                derived from repository files, which are attacker-supplied — rendering it as HTML
                would turn a crafted README into stored XSS. Plain text cannot.
              */}
              <pre className="overflow-x-auto whitespace-pre-wrap wrap-break-word font-mono text-sm text-slate-300">
                {current.data.content}
              </pre>
            </Card>
          )}
        </>
      )}
    </div>
  );
}
