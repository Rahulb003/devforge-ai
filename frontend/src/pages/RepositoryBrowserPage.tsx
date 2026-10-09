import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ArrowLeft,
  BookText,
  File as FileIcon,
  FilePlus,
  FileWarning,
  Folder,
  GitBranch,
  GitPullRequest,
  History,
  Pencil,
  ShieldCheck,
  Trash2,
  Undo2,
} from 'lucide-react';
import { lazy, Suspense, useEffect, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';

import type { FileChange, TreeEntry } from '@/api/git.api';
import { gitApi } from '@/api/git.api';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { Input } from '@/components/ui/Input';
import { EmptyState, ErrorState, LoadingState, Skeleton } from '@/components/ui/states';
import { describeApiError } from '@/lib/errors';

// Loaded on first edit: most visits only read, and the editor is most of this page's weight.
const CodeEditor = lazy(() => import('@/components/editor/CodeEditor'));

/** A file in the working set: its content at the base commit, and now. `current: null` deletes. */
interface StagedFile {
  original: string;
  current: string | null;
}

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
  const [branching, setBranching] = useState(false);
  const [branchName, setBranchName] = useState('');
  const [branchError, setBranchError] = useState<string | null>(null);
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

  /*
   * The editor's working set: edits and deletions across files, committed together.
   *
   * Every file is loaded at one pinned commit (baseCommit), not at the branch name. Loading by
   * branch would let a file fetched before someone else's commit be committed "on top" of it, and
   * the server's moved-branch check could not catch that: the base would be current while the
   * content was stale, silently reverting their change.
   */
  const [staged, setStaged] = useState<Record<string, StagedFile>>({});
  const [baseCommit, setBaseCommit] = useState<string | null>(null);
  const [editing, setEditing] = useState(false);
  const [editMessage, setEditMessage] = useState('');
  const [editError, setEditError] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);

  const changes: FileChange[] = Object.entries(staged)
    .filter(([, file]) => file.current !== file.original)
    .map(([stagedPath, file]) =>
      file.current === null
        ? { path: stagedPath, delete: true as const }
        : { path: stagedPath, content: file.current },
    );
  const hasChanges = changes.length > 0;

  // Uncommitted edits exist only in this tab; closing it must not lose them silently.
  useEffect(() => {
    if (!hasChanges) return;
    const warn = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [hasChanges]);

  /** Pins the branch head the first time anything is staged, fetched fresh rather than cached. */
  async function pinBase(): Promise<string | null> {
    if (baseCommit) return baseCommit;
    const fresh = await queryClient.fetchQuery({
      queryKey: ['repository-branches', repositoryId],
      queryFn: async () =>
        (await gitApi.branches(organizationId, projectId, repositoryId)).data.data,
      staleTime: 0,
    });
    const head = fresh.find((branch) => branch.name === ref)?.commitId ?? null;
    if (!head) setEditError('Editing works on a branch. Choose one to edit.');
    setBaseCommit(head);
    return head;
  }

  async function startEditing(target: string) {
    setEditError(null);
    try {
      const base = await pinBase();
      if (!base) return;
      if (!staged[target]) {
        const file = (await gitApi.blob(organizationId, projectId, repositoryId, target, base)).data
          .data;
        if (file.binary || file.truncated) {
          setEditError('This file is binary or too large to edit here.');
          return;
        }
        const content = file.content ?? '';
        setStaged((all) => ({ ...all, [target]: { original: content, current: content } }));
      }
      setEditing(true);
    } catch (err) {
      setEditError(describeApiError(err));
    }
  }

  async function stageDeletion(target: string) {
    setEditError(null);
    try {
      const base = await pinBase();
      if (!base) return;
      setStaged((all) => ({
        ...all,
        [target]: { original: all[target]?.original ?? '', current: null },
      }));
      setEditing(false);
    } catch (err) {
      setEditError(describeApiError(err));
    }
  }

  function discard(target?: string) {
    if (target === undefined) {
      setStaged({});
      setBaseCommit(null);
      setConflict(false);
      setEditing(false);
      setEditError(null);
      return;
    }
    setStaged((all) => {
      const rest = { ...all };
      delete rest[target];
      return rest;
    });
    if (target === filePath) setEditing(false);
  }

  const commitChanges = useMutation({
    mutationFn: () =>
      gitApi.commitChanges(organizationId, projectId, repositoryId, {
        message: editMessage.trim(),
        branch: ref,
        baseCommitId: baseCommit ?? '',
        changes,
      }),
    onSuccess: () => {
      setStaged({});
      setBaseCommit(null);
      setEditing(false);
      setEditMessage('');
      setEditError(null);
      setConflict(false);
      void queryClient.invalidateQueries({ queryKey: ['repository'] });
      void queryClient.invalidateQueries({ queryKey: ['repository-tree'] });
      void queryClient.invalidateQueries({ queryKey: ['repository-blob'] });
      void queryClient.invalidateQueries({ queryKey: ['repository-commits'] });
      void queryClient.invalidateQueries({ queryKey: ['repository-branches'] });
    },
    onError: (err) => {
      setEditError(describeApiError(err));
      setConflict(
        typeof err === 'object' &&
          err !== null &&
          'response' in err &&
          (err as { response?: { status?: number } }).response?.status === 409,
      );
    },
  });

  /** Branches from whatever is being viewed, then switches to the new branch. */
  const createBranch = useMutation({
    mutationFn: () =>
      gitApi.createBranch(organizationId, projectId, repositoryId, branchName.trim(), ref),
    onSuccess: (response) => {
      setBranching(false);
      setBranchName('');
      setBranchError(null);
      void queryClient.invalidateQueries({ queryKey: ['repository-branches'] });
      navigate({ ref: response.data.data.name, path: '', file: '' });
    },
    // The server owns branch-name rules (and duplicates), so its message is the useful one.
    onError: (err) => setBranchError(describeApiError(err)),
  });

  /**
   * Changes only the keys given: '' clears one, an absent key is left alone.
   *
   * It used to clear every key it was not given, so opening a file - which passes only `file` -
   * dropped `ref` and showed the default branch's copy. With the editor that was worse: the edit
   * was committed to the default branch instead of the one on screen.
   */
  function navigate(next: { path?: string; file?: string; ref?: string }) {
    const params = new URLSearchParams(searchParams);
    for (const key of ['path', 'file', 'ref'] as const) {
      if (!(key in next)) continue;
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

          {!isEmpty && repository.isSuccess && (
            <Link
              to={`/organizations/${organizationId}/projects/${projectId}/repositories/${repositoryId}/pull-requests`}
              className="inline-flex h-11 items-center gap-2 rounded-xl border border-slate-700 bg-slate-800 px-5 text-sm font-medium text-slate-100 transition hover:border-slate-600 focus-visible:outline-solid focus-visible:outline-2 focus-visible:outline-indigo-400"
            >
              <GitPullRequest className="h-4 w-4" aria-hidden="true" />
              Pull requests
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
                // The working set belongs to one branch's commit; switching would orphan it.
                disabled={hasChanges}
                title={hasChanges ? 'Commit or discard your changes to switch branch' : undefined}
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

          {!isEmpty && repository.isSuccess && !branching && (
            <Button
              variant="secondary"
              leftIcon={<GitBranch className="h-4 w-4" />}
              onClick={() => setBranching(true)}
              disabled={hasChanges}
            >
              New branch
            </Button>
          )}
        </div>
      </header>

      {branching && (
        <Card>
          <form
            aria-label="New branch"
            className="flex flex-wrap items-end gap-3"
            onSubmit={(event) => {
              event.preventDefault();
              if (branchName.trim()) createBranch.mutate();
            }}
          >
            <div className="min-w-64 flex-1">
              <Input
                label="Branch name"
                value={branchName}
                onChange={(event) => setBranchName(event.target.value)}
                placeholder="feature/login"
                hint={`Starts from ${ref ?? 'the default branch'}.`}
              />
            </div>
            <Button type="submit" loading={createBranch.isPending} disabled={!branchName.trim()}>
              Create branch
            </Button>
            <Button
              type="button"
              variant="secondary"
              onClick={() => {
                setBranching(false);
                setBranchError(null);
              }}
            >
              Cancel
            </Button>
            {branchError && (
              <p
                role="alert"
                className="w-full rounded-xl border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-300"
              >
                {branchError}
              </p>
            )}
          </form>
        </Card>
      )}

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
                    <span className="text-slate-400">/</span>
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

          {hasChanges && (
            <ChangesPanel
              changes={changes}
              message={editMessage}
              onMessage={setEditMessage}
              onOpen={(target) => navigate({ file: target })}
              onDiscard={discard}
              onCommit={() => commitChanges.mutate()}
              committing={commitChanges.isPending}
              error={editError}
              conflict={conflict}
            />
          )}

          {!hasChanges && editError && (
            <p
              role="alert"
              className="rounded-xl border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-300"
            >
              {editError}
            </p>
          )}

          {tab === 'files' && filePath && editing && staged[filePath]?.current != null && (
            <div className="space-y-3">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <span className="font-mono text-sm text-slate-300">{filePath}</span>
                <div className="flex gap-2">
                  <Button variant="secondary" onClick={() => setEditing(false)}>
                    Done editing
                  </Button>
                  <Button
                    variant="secondary"
                    leftIcon={<Undo2 className="h-4 w-4" />}
                    onClick={() => discard(filePath)}
                  >
                    Discard changes to this file
                  </Button>
                </div>
              </div>
              <Suspense fallback={<LoadingState label="Loading editor…" />}>
                <CodeEditor
                  path={filePath}
                  value={staged[filePath]?.current ?? ''}
                  onChange={(next) =>
                    setStaged((all) => {
                      const file = all[filePath];
                      return file ? { ...all, [filePath]: { ...file, current: next } } : all;
                    })
                  }
                />
              </Suspense>
            </div>
          )}

          {tab === 'files' && filePath && !(editing && staged[filePath]?.current != null) && (
            <>
              {staged[filePath] && staged[filePath].current !== staged[filePath].original && (
                <Badge tone="warning">
                  {staged[filePath].current === null
                    ? 'Marked for deletion — not committed yet'
                    : 'Edited — not committed yet'}
                </Badge>
              )}
              <FileView
                isLoading={blob.isLoading}
                isError={blob.isError}
                error={blob.error}
                blob={blob.data}
                onRetry={() => void blob.refetch()}
                onEdit={() => void startEditing(filePath)}
                onDelete={() => void stageDeletion(filePath)}
              />
            </>
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
                <FileIcon className="h-4 w-4 shrink-0 text-slate-400" aria-hidden="true" />
              )}
              <span className="flex-1 truncate text-sm text-slate-200">{entry.name}</span>
              {/* Announced as part of the row, since the type is otherwise conveyed by icon alone. */}
              <span className="sr-only">{entry.type === 'DIRECTORY' ? 'directory' : 'file'}</span>
              <span className="shrink-0 text-xs text-slate-400">{formatBytes(entry.size)}</span>
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
  onEdit,
  onDelete,
}: {
  isLoading: boolean;
  isError: boolean;
  error: unknown;
  blob: import('@/api/git.api').Blob | undefined;
  onRetry: () => void;
  onEdit: () => void;
  onDelete: () => void;
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
        <span className="ml-auto flex gap-2">
          {/* A truncated file cannot be edited: committing it would cut off everything unseen. */}
          {!blob.truncated && (
            <Button
              size="sm"
              variant="secondary"
              leftIcon={<Pencil className="h-4 w-4" />}
              onClick={onEdit}
            >
              Edit
            </Button>
          )}
          <Button
            size="sm"
            variant="ghost"
            leftIcon={<Trash2 className="h-4 w-4" />}
            onClick={onDelete}
          >
            Delete
          </Button>
        </span>
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
                  className="w-12 select-none border-r border-slate-800 px-3 py-0.5 text-right align-top text-xs text-slate-400"
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

/** The uncommitted changes across files, and the form that commits them as one. */
function ChangesPanel({
  changes,
  message,
  onMessage,
  onOpen,
  onDiscard,
  onCommit,
  committing,
  error,
  conflict,
}: {
  changes: FileChange[];
  message: string;
  onMessage: (message: string) => void;
  onOpen: (path: string) => void;
  onDiscard: (path?: string) => void;
  onCommit: () => void;
  committing: boolean;
  error: string | null;
  conflict: boolean;
}) {
  const label = `Commit ${changes.length} ${changes.length === 1 ? 'change' : 'changes'}`;
  return (
    <Card>
      <form
        aria-label="Uncommitted changes"
        className="space-y-4"
        onSubmit={(event) => {
          event.preventDefault();
          if (message.trim()) onCommit();
        }}
      >
        <h2 className="text-lg font-semibold text-white">Uncommitted changes</h2>
        <ul className="divide-y divide-slate-800">
          {changes.map((change) => (
            <li key={change.path} className="flex items-center justify-between gap-3 py-2">
              <button
                type="button"
                onClick={() => onOpen(change.path)}
                className="truncate font-mono text-sm text-indigo-300 underline-offset-4 hover:underline"
              >
                {change.path}
              </button>
              <span className="flex items-center gap-2">
                <Badge tone={'delete' in change ? 'danger' : 'info'}>
                  {'delete' in change ? 'Deleted' : 'Modified'}
                </Badge>
                <Button
                  type="button"
                  size="sm"
                  variant="ghost"
                  aria-label={`Discard changes to ${change.path}`}
                  onClick={() => onDiscard(change.path)}
                >
                  <Undo2 className="h-4 w-4" aria-hidden="true" />
                </Button>
              </span>
            </li>
          ))}
        </ul>
        <Input
          label="Commit message"
          value={message}
          onChange={(event) => onMessage(event.target.value)}
          placeholder="Describe the change"
        />
        {error && (
          <p
            role="alert"
            className="rounded-xl border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-300"
          >
            {error}
          </p>
        )}
        <div className="flex flex-wrap gap-3">
          <Button type="submit" loading={committing} disabled={!message.trim()}>
            {label}
          </Button>
          <Button type="button" variant="secondary" onClick={() => onDiscard()}>
            {conflict ? 'Discard all and reload' : 'Discard all'}
          </Button>
        </div>
      </form>
    </Card>
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
            <p className="mt-1 text-xs text-slate-400">
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
