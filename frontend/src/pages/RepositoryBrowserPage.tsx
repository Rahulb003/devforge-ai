import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ArrowLeft,
  BookText,
  File as FileIcon,
  FilePlus,
  FileWarning,
  Folder,
  GitBranch,
  History,
  ShieldCheck,
} from 'lucide-react';
import { useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';

import type { TreeEntry } from '@/api/git.api';
import { gitApi } from '@/api/git.api';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { Input } from '@/components/ui/Input';
import { EmptyState, ErrorState, LoadingState, Skeleton } from '@/components/ui/states';
import { describeApiError } from '@/lib/errors';

function formatBytes(bytes: number | null): string {
  if (bytes === null) return '';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function relativeTime(iso: string): string {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return '';
  const minutes = Math.round((Date.now() - then) / 60000);
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.round(hours / 24);
  if (days < 30) return `${days}d ago`;
  return new Date(iso).toLocaleDateString();
}

/**
 * Browse a repository: files, history, and one file's contents.
 *
 * The current directory and ref live in the URL rather than in component state,
 * so a link to a file is a link someone else can open, and the browser's back
 * button walks back up the tree as the reader expects.
 */
export function RepositoryBrowserPage() {
  const { organizationId = '', projectId = '', repositoryId = '' } = useParams();
  const [searchParams, setSearchParams] = useSearchParams();
  const [tab, setTab] = useState<'files' | 'history'>('files');
  const [adding, setAdding] = useState(false);
  const [newPath, setNewPath] = useState('');
  const [newContent, setNewContent] = useState('');
  const [message, setMessage] = useState('');
  const [commitError, setCommitError] = useState<string | null>(null);
  const queryClient = useQueryClient();

  const path = searchParams.get('path') ?? '';
  const refParam = searchParams.get('ref') ?? undefined;
  const filePath = searchParams.get('file') ?? undefined;

  const repository = useQuery({
    queryKey: ['repository', organizationId, projectId, repositoryId],
    queryFn: async () => (await gitApi.get(organizationId, projectId, repositoryId)).data.data,
    enabled: Boolean(organizationId && projectId && repositoryId),
  });

  const ref = refParam ?? repository.data?.defaultBranch;
  const isEmpty = repository.data?.empty === true;
  /*
   * Gate on the repository having actually loaded, not just on isEmpty.
   *
   * Before it resolves, repository.data is undefined and isEmpty reads false — so every
   * dependent query below fired at once on first render, including against a repository with
   * no commits, where the request can only 404.
   */
  const canBrowse = repository.isSuccess && !isEmpty;

  const branches = useQuery({
    queryKey: ['repository-branches', repositoryId],
    queryFn: async () => (await gitApi.branches(organizationId, projectId, repositoryId)).data.data,
    // Nothing to list before the first commit, and asking would only 404.
    enabled: Boolean(repositoryId) && canBrowse,
  });

  const tree = useQuery({
    queryKey: ['repository-tree', repositoryId, ref, path],
    queryFn: async () =>
      (await gitApi.tree(organizationId, projectId, repositoryId, ref, path || undefined)).data
        .data,
    enabled: Boolean(repositoryId && ref) && canBrowse && !filePath,
  });

  const blob = useQuery({
    queryKey: ['repository-blob', repositoryId, ref, filePath],
    queryFn: async () =>
      (await gitApi.blob(organizationId, projectId, repositoryId, filePath ?? '', ref)).data.data,
    enabled: Boolean(repositoryId && ref && filePath) && canBrowse,
  });

  const commits = useQuery({
    queryKey: ['repository-commits', repositoryId, ref],
    queryFn: async () =>
      (await gitApi.commits(organizationId, projectId, repositoryId, ref)).data.data,
    enabled: Boolean(repositoryId && ref) && canBrowse && tab === 'history',
  });

  /**
   * Commits one file.
   *
   * Without this the UI is a dead end: a new repository is empty, and there would be no way to
   * put anything in it short of calling the API by hand.
   */
  const commitFile = useMutation({
    mutationFn: () =>
      gitApi.commitFile(organizationId, projectId, repositoryId, {
        path: newPath.trim(),
        content: newContent,
        message: message.trim(),
        branch: refParam,
      }),
    onSuccess: () => {
      setAdding(false);
      setNewPath('');
      setNewContent('');
      setMessage('');
      setCommitError(null);
      // The repository itself is invalidated too: its `empty` flag has just changed, and the
      // browse queries are gated on it.
      void queryClient.invalidateQueries({ queryKey: ['repository'] });
      void queryClient.invalidateQueries({ queryKey: ['repository-tree'] });
      void queryClient.invalidateQueries({ queryKey: ['repository-commits'] });
      void queryClient.invalidateQueries({ queryKey: ['repository-branches'] });
    },
    // The server owns path validation, so its message is the useful one.
    onError: (err) => setCommitError(describeApiError(err)),
  });

  function navigate(next: { path?: string; file?: string; ref?: string }) {
    const params = new URLSearchParams(searchParams);
    for (const key of ['path', 'file', 'ref'] as const) {
      const value = next[key];
      if (value === undefined || value === '') params.delete(key);
      else params.set(key, value);
    }
    setSearchParams(params);
  }

  function openEntry(entry: TreeEntry) {
    if (entry.type === 'DIRECTORY') {
      navigate({ path: entry.path, file: '' });
    } else {
      navigate({ file: entry.path });
    }
  }

  // Breadcrumbs for whichever of the two the reader is looking at.
  const currentPath = filePath ?? path;
  const segments = currentPath ? currentPath.split('/') : [];

  return (
    <div className="space-y-6">
      <Link
        to={`/organizations/${organizationId}/projects/${projectId}/repositories`}
        className="inline-flex items-center gap-2 text-sm text-slate-400 underline-offset-4 hover:text-slate-200 hover:underline"
      >
        <ArrowLeft className="h-4 w-4" aria-hidden="true" />
        All repositories
      </Link>

      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          {repository.isLoading ? (
            <Skeleton className="h-8 w-56" />
          ) : (
            <>
              <h1 className="text-2xl font-semibold text-white">{repository.data?.name}</h1>
              {repository.data?.description && (
                <p className="mt-1 text-sm text-slate-400">{repository.data.description}</p>
              )}
            </>
          )}
        </div>

        <div className="flex items-center gap-3">
          {!isEmpty && repository.isSuccess && (
            <Link
              to={`/organizations/${organizationId}/projects/${projectId}/repositories/${repositoryId}/review`}
              className="inline-flex h-11 items-center gap-2 rounded-xl border border-slate-700 bg-slate-800 px-5 text-sm font-medium text-slate-100 transition hover:border-slate-600 focus-visible:outline-solid focus-visible:outline-2 focus-visible:outline-indigo-400"
            >
              <ShieldCheck className="h-4 w-4" aria-hidden="true" />
              Review
            </Link>
          )}

          {!isEmpty && repository.isSuccess && (
            <Link
              to={`/organizations/${organizationId}/projects/${projectId}/repositories/${repositoryId}/docs`}
              className="inline-flex h-11 items-center gap-2 rounded-xl border border-slate-700 bg-slate-800 px-5 text-sm font-medium text-slate-100 transition hover:border-slate-600 focus-visible:outline-solid focus-visible:outline-2 focus-visible:outline-indigo-400"
            >
              <BookText className="h-4 w-4" aria-hidden="true" />
              Docs
            </Link>
          )}

          {!isEmpty && !adding && repository.isSuccess && (
            <Button
              variant="secondary"
              leftIcon={<FilePlus className="h-4 w-4" />}
              onClick={() => setAdding(true)}
            >
              Add a file
            </Button>
          )}

          {!isEmpty && branches.data && branches.data.length > 0 && (
            <label className="flex items-center gap-2 text-sm text-slate-400">
              <GitBranch className="h-4 w-4" aria-hidden="true" />
              <span className="sr-only">Branch</span>
              <select
                value={ref ?? ''}
                onChange={(event) => navigate({ ref: event.target.value, path: '', file: '' })}
                className="rounded-xl border border-slate-700 bg-slate-800 px-3 py-2 text-sm text-slate-100 focus-visible:outline-solid focus-visible:outline-2 focus-visible:outline-indigo-400"
              >
                {branches.data.map((branch) => (
                  <option key={branch.name} value={branch.name}>
                    {branch.name}
                    {branch.isDefault ? ' (default)' : ''}
                  </option>
                ))}
              </select>
            </label>
          )}
        </div>
      </header>

      {repository.isError && (
        <ErrorState
          message={describeApiError(repository.error)}
          onRetry={() => void repository.refetch()}
        />
      )}

      {isEmpty && !adding && (
        <EmptyState
          icon={<GitBranch className="h-6 w-6" aria-hidden="true" />}
          title="This repository is empty"
          description="There are no commits yet. Add a file to make the first commit."
          action={
            <Button leftIcon={<FilePlus className="h-4 w-4" />} onClick={() => setAdding(true)}>
              Add a file
            </Button>
          }
        />
      )}

      {adding && (
        <Card>
          <form
            onSubmit={(event) => {
              event.preventDefault();
              if (newPath.trim() && message.trim()) commitFile.mutate();
            }}
            className="space-y-4"
          >
            <h2 className="text-lg font-semibold text-white">Add a file</h2>
            <Input
              label="Path"
              value={newPath}
              onChange={(event) => setNewPath(event.target.value)}
              placeholder="src/App.java"
              hint="Relative to the repository root."
            />
            <div className="space-y-1.5">
              <label htmlFor="file-content" className="block text-sm font-medium text-slate-300">
                Content
              </label>
              <textarea
                id="file-content"
                value={newContent}
                onChange={(event) => setNewContent(event.target.value)}
                rows={10}
                className="w-full rounded-xl border border-slate-700 bg-slate-800 px-4 py-3 font-mono text-sm text-slate-100 focus-visible:outline-solid focus-visible:outline-2 focus-visible:outline-indigo-400"
              />
            </div>
            <Input
              label="Commit message"
              value={message}
              onChange={(event) => setMessage(event.target.value)}
              placeholder="Add App"
            />

            {commitError && (
              <p
                role="alert"
                className="rounded-xl border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-300"
              >
                {commitError}
              </p>
            )}

            <div className="flex gap-3">
              <Button
                type="submit"
                loading={commitFile.isPending}
                disabled={!newPath.trim() || !message.trim()}
              >
                Commit
              </Button>
              <Button
                type="button"
                variant="secondary"
                onClick={() => {
                  setAdding(false);
                  setCommitError(null);
                }}
              >
                Cancel
              </Button>
            </div>
          </form>
        </Card>
      )}

      {!isEmpty && repository.isSuccess && (
        <>
          <div className="flex gap-2 border-b border-slate-800" role="tablist">
            {(['files', 'history'] as const).map((value) => (
              <button
                key={value}
                type="button"
                role="tab"
                aria-selected={tab === value}
                onClick={() => setTab(value)}
                className={`-mb-px border-b-2 px-4 py-2 text-sm font-medium transition ${
                  tab === value
                    ? 'border-indigo-400 text-white'
                    : 'border-transparent text-slate-400 hover:text-slate-200'
                }`}
              >
                {value === 'files' ? 'Files' : 'History'}
              </button>
            ))}
          </div>

          {segments.length > 0 && (
            <nav aria-label="Breadcrumb" className="flex flex-wrap items-center gap-1 text-sm">
              <button
                type="button"
                onClick={() => navigate({ path: '', file: '' })}
                className="text-indigo-400 underline-offset-4 hover:underline"
              >
                {repository.data?.name}
              </button>
              {segments.map((segment, index) => {
                const isLast = index === segments.length - 1;
                const upTo = segments.slice(0, index + 1).join('/');
                return (
                  <span key={upTo} className="flex items-center gap-1">
                    <span className="text-slate-600">/</span>
                    {isLast ? (
                      <span className="text-slate-300">{segment}</span>
                    ) : (
                      <button
                        type="button"
                        onClick={() => navigate({ path: upTo, file: '' })}
                        className="text-indigo-400 underline-offset-4 hover:underline"
                      >
                        {segment}
                      </button>
                    )}
                  </span>
                );
              })}
            </nav>
          )}

          {tab === 'files' && !filePath && (
            <TreeListing
              isLoading={tree.isLoading}
              isError={tree.isError}
              error={tree.error}
              entries={tree.data ?? []}
              onRetry={() => void tree.refetch()}
              onOpen={openEntry}
            />
          )}

          {tab === 'files' && filePath && (
            <FileView
              isLoading={blob.isLoading}
              isError={blob.isError}
              error={blob.error}
              blob={blob.data}
              onRetry={() => void blob.refetch()}
            />
          )}

          {tab === 'history' && (
            <CommitList
              isLoading={commits.isLoading}
              isError={commits.isError}
              error={commits.error}
              commits={commits.data ?? []}
              onRetry={() => void commits.refetch()}
            />
          )}
        </>
      )}
    </div>
  );
}

function TreeListing({
  isLoading,
  isError,
  error,
  entries,
  onRetry,
  onOpen,
}: {
  isLoading: boolean;
  isError: boolean;
  error: unknown;
  entries: TreeEntry[];
  onRetry: () => void;
  onOpen: (entry: TreeEntry) => void;
}) {
  if (isLoading) return <LoadingState label="Loading files…" />;
  if (isError) return <ErrorState message={describeApiError(error)} onRetry={onRetry} />;
  if (entries.length === 0) {
    return (
      <EmptyState
        icon={<Folder className="h-6 w-6" aria-hidden="true" />}
        title="Nothing here"
        description="This directory has no entries at this ref."
      />
    );
  }

  return (
    <Card className="divide-y divide-slate-800 p-0">
      <ul>
        {entries.map((entry) => (
          <li key={entry.path}>
            <button
              type="button"
              onClick={() => onOpen(entry)}
              className="flex w-full items-center gap-3 px-5 py-3 text-left transition hover:bg-slate-800/60 focus-visible:outline-solid focus-visible:outline-2 focus-visible:outline-indigo-400"
            >
              {entry.type === 'DIRECTORY' ? (
                <Folder className="h-4 w-4 shrink-0 text-indigo-400" aria-hidden="true" />
              ) : (
                <FileIcon className="h-4 w-4 shrink-0 text-slate-500" aria-hidden="true" />
              )}
              <span className="flex-1 truncate text-sm text-slate-200">{entry.name}</span>
              {/* Announced as part of the row, since the type is otherwise conveyed by icon alone. */}
              <span className="sr-only">{entry.type === 'DIRECTORY' ? 'directory' : 'file'}</span>
              <span className="shrink-0 text-xs text-slate-500">{formatBytes(entry.size)}</span>
            </button>
          </li>
        ))}
      </ul>
    </Card>
  );
}

function FileView({
  isLoading,
  isError,
  error,
  blob,
  onRetry,
}: {
  isLoading: boolean;
  isError: boolean;
  error: unknown;
  blob: import('@/api/git.api').Blob | undefined;
  onRetry: () => void;
}) {
  if (isLoading) return <LoadingState label="Loading file…" />;
  if (isError) return <ErrorState message={describeApiError(error)} onRetry={onRetry} />;
  if (!blob) return null;

  if (blob.binary) {
    return (
      <EmptyState
        icon={<FileWarning className="h-6 w-6" aria-hidden="true" />}
        title="Binary file"
        description={`${formatBytes(blob.size)} — not shown, because rendering it as text would look like corruption.`}
      />
    );
  }

  const lines = (blob.content ?? '').split('\n');

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-3 text-sm text-slate-400">
        <span className="font-mono text-slate-300">{blob.path}</span>
        <span>{formatBytes(blob.size)}</span>
        {blob.truncated && (
          <Badge tone="warning">Truncated — showing the first part of the file</Badge>
        )}
      </div>

      <Card className="overflow-x-auto p-0">
        {/* Line numbers in a separate column so selecting the code does not copy them. */}
        <table className="w-full border-collapse font-mono text-sm">
          <tbody>
            {lines.map((line, index) => (
              // eslint-disable-next-line react/no-array-index-key
              <tr key={index} className="hover:bg-slate-800/40">
                <td
                  aria-hidden="true"
                  className="w-12 select-none border-r border-slate-800 px-3 py-0.5 text-right align-top text-xs text-slate-600"
                >
                  {index + 1}
                </td>
                <td className="whitespace-pre-wrap wrap-break-word px-4 py-0.5 text-slate-200">
                  {line || ' '}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
    </div>
  );
}

function CommitList({
  isLoading,
  isError,
  error,
  commits,
  onRetry,
}: {
  isLoading: boolean;
  isError: boolean;
  error: unknown;
  commits: import('@/api/git.api').Commit[];
  onRetry: () => void;
}) {
  if (isLoading) return <LoadingState label="Loading history…" />;
  if (isError) return <ErrorState message={describeApiError(error)} onRetry={onRetry} />;
  if (commits.length === 0) {
    return (
      <EmptyState
        icon={<History className="h-6 w-6" aria-hidden="true" />}
        title="No commits"
        description="Nothing has been committed on this ref."
      />
    );
  }

  return (
    <Card className="divide-y divide-slate-800 p-0">
      <ul>
        {commits.map((commit) => (
          <li key={commit.id} className="px-5 py-4">
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              {/* Only the first line: a commit body can be long, and this is a list. */}
              <p className="font-medium text-slate-100">{commit.message.split('\n')[0]}</p>
              <code className="shrink-0 rounded bg-slate-800 px-2 py-0.5 text-xs text-slate-400">
                {commit.shortId}
              </code>
            </div>
            <p className="mt-1 text-xs text-slate-500">
              {commit.authorName ?? 'unknown'}
              {' · '}
              <time dateTime={commit.committedAt}>{relativeTime(commit.committedAt)}</time>
              {commit.parentIds.length > 1 && ' · merge'}
            </p>
          </li>
        ))}
      </ul>
    </Card>
  );
}
