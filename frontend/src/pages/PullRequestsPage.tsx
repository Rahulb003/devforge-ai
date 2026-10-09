import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  AlertTriangle,
  ArrowLeft,
  CheckCircle2,
  GitMerge,
  GitPullRequest,
  XCircle,
} from 'lucide-react';
import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';

import type { PullRequest, PullRequestStatus } from '@/api/git.api';
import { gitApi, pullRequestApi } from '@/api/git.api';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { Input } from '@/components/ui/Input';
import { EmptyState, ErrorState, LoadingState } from '@/components/ui/states';
import { describeApiError } from '@/lib/errors';
import { useAuthStore } from '@/stores/authStore';

const STATUS_TONE: Record<PullRequestStatus, 'info' | 'success' | 'neutral'> = {
  OPEN: 'info',
  MERGED: 'success',
  CLOSED: 'neutral',
};

const STATUS_LABEL: Record<PullRequestStatus, string> = {
  OPEN: 'Open',
  MERGED: 'Merged',
  CLOSED: 'Closed',
};

function useRepositoryParams() {
  const { organizationId = '', projectId = '', repositoryId = '' } = useParams();
  const repositoryPath = `/organizations/${organizationId}/projects/${projectId}/repositories/${repositoryId}`;
  return { organizationId, projectId, repositoryId, repositoryPath };
}

/** The list of a repository's pull requests, and the form that opens one. */
export function PullRequestsPage() {
  const { organizationId, projectId, repositoryId, repositoryPath } = useRepositoryParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [filter, setFilter] = useState<PullRequestStatus | undefined>('OPEN');
  const [opening, setOpening] = useState(false);
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [source, setSource] = useState('');
  const [target, setTarget] = useState('');
  const [error, setError] = useState<string | null>(null);

  const repository = useQuery({
    queryKey: ['repository', organizationId, projectId, repositoryId],
    queryFn: async () => (await gitApi.get(organizationId, projectId, repositoryId)).data.data,
  });
  const branches = useQuery({
    queryKey: ['repository-branches', repositoryId],
    queryFn: async () => (await gitApi.branches(organizationId, projectId, repositoryId)).data.data,
    enabled: repository.isSuccess && !repository.data.empty,
  });
  const list = useQuery({
    queryKey: ['pull-requests', repositoryId, filter],
    queryFn: async () =>
      (await pullRequestApi.list(organizationId, projectId, repositoryId, filter)).data.data,
  });

  const targetBranch = target || repository.data?.defaultBranch || '';
  const open = useMutation({
    mutationFn: () =>
      pullRequestApi.open(organizationId, projectId, repositoryId, {
        title: title.trim(),
        description: description.trim() || undefined,
        sourceBranch: source,
        targetBranch,
      }),
    onSuccess: (response) => {
      // Queries stay fresh for five minutes app-wide, so without this the list kept showing its
      // old contents - a just-opened pull request was missing from it until the cache expired.
      void queryClient.invalidateQueries({ queryKey: ['pull-requests', repositoryId] });
      navigate(`${repositoryPath}/pull-requests/${response.data.data.number}`);
    },
    // The server decides what can be proposed (nothing to merge, a duplicate), so its words are used.
    onError: (err) => setError(describeApiError(err)),
  });

  const sources = (branches.data ?? []).filter((branch) => branch.name !== targetBranch);

  const [rule, setRule] = useState<string | null>(null);
  const [ruleError, setRuleError] = useState<string | null>(null);
  const saveRule = useMutation({
    mutationFn: () =>
      pullRequestApi.setRequiredApprovals(organizationId, projectId, repositoryId, Number(rule)),
    onSuccess: () => {
      setRule(null);
      setRuleError(null);
      void queryClient.invalidateQueries({ queryKey: ['repository'] });
      // Each pull request reports the rule it is held to; a cached one would still show the old.
      void queryClient.invalidateQueries({ queryKey: ['pull-request', repositoryId] });
    },
    // Admins only: anyone else sees the server's refusal rather than a control that silently fails.
    onError: (err) => setRuleError(describeApiError(err)),
  });

  return (
    <div className="space-y-6">
      <Link
        to={repositoryPath}
        className="inline-flex items-center gap-2 text-sm text-slate-400 underline-offset-4 hover:text-slate-200 hover:underline"
      >
        <ArrowLeft className="h-4 w-4" aria-hidden="true" />
        Back to {repository.data?.name ?? 'repository'}
      </Link>

      <header className="flex flex-wrap items-center justify-between gap-4">
        <h1 className="text-2xl font-semibold text-white">Pull requests</h1>
        {!opening && (
          <Button
            leftIcon={<GitPullRequest className="h-4 w-4" />}
            onClick={() => setOpening(true)}
            disabled={(branches.data?.length ?? 0) < 2}
            title={
              (branches.data?.length ?? 0) < 2
                ? 'Create a second branch to propose a change'
                : undefined
            }
          >
            New pull request
          </Button>
        )}
      </header>

      {repository.isSuccess && (
        <form
          aria-label="Merge rules"
          className="flex flex-wrap items-end gap-3 text-sm"
          onSubmit={(event) => {
            event.preventDefault();
            if (rule !== null) saveRule.mutate();
          }}
        >
          <label className="space-y-1.5 text-slate-300">
            <span className="block">Approvals required to merge</span>
            <input
              type="number"
              min={0}
              max={10}
              value={rule ?? String(repository.data.requiredApprovals ?? 0)}
              onChange={(event) => setRule(event.target.value)}
              className="w-24 rounded-xl border border-slate-700 bg-slate-800 px-3 py-2 text-slate-100"
            />
          </label>
          {rule !== null && (
            <Button type="submit" size="sm" loading={saveRule.isPending}>
              Save
            </Button>
          )}
          {ruleError && (
            <p role="alert" className="w-full text-sm text-red-300">
              {ruleError}
            </p>
          )}
        </form>
      )}

      {opening && (
        <Card>
          <form
            aria-label="New pull request"
            className="space-y-4"
            onSubmit={(event) => {
              event.preventDefault();
              if (title.trim() && source) open.mutate();
            }}
          >
            <div className="grid gap-4 sm:grid-cols-2">
              <label className="space-y-1.5 text-sm font-medium text-slate-300">
                <span className="block">From branch</span>
                <select
                  value={source}
                  onChange={(event) => setSource(event.target.value)}
                  className="w-full rounded-xl border border-slate-700 bg-slate-800 px-3 py-2 text-slate-100"
                >
                  <option value="">Choose a branch</option>
                  {sources.map((branch) => (
                    <option key={branch.name} value={branch.name}>
                      {branch.name}
                    </option>
                  ))}
                </select>
              </label>
              <label className="space-y-1.5 text-sm font-medium text-slate-300">
                <span className="block">Into branch</span>
                <select
                  value={targetBranch}
                  onChange={(event) => setTarget(event.target.value)}
                  className="w-full rounded-xl border border-slate-700 bg-slate-800 px-3 py-2 text-slate-100"
                >
                  {(branches.data ?? []).map((branch) => (
                    <option key={branch.name} value={branch.name}>
                      {branch.name}
                    </option>
                  ))}
                </select>
              </label>
            </div>
            <Input label="Title" value={title} onChange={(event) => setTitle(event.target.value)} />
            <div className="space-y-1.5">
              <label htmlFor="pr-description" className="block text-sm font-medium text-slate-300">
                Description
              </label>
              <textarea
                id="pr-description"
                value={description}
                onChange={(event) => setDescription(event.target.value)}
                rows={4}
                className="w-full rounded-xl border border-slate-700 bg-slate-800 px-4 py-3 text-sm text-slate-100"
              />
            </div>
            {error && (
              <p
                role="alert"
                className="rounded-xl border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-300"
              >
                {error}
              </p>
            )}
            <div className="flex gap-3">
              <Button type="submit" loading={open.isPending} disabled={!title.trim() || !source}>
                Open pull request
              </Button>
              <Button
                type="button"
                variant="secondary"
                onClick={() => {
                  setOpening(false);
                  setError(null);
                }}
              >
                Cancel
              </Button>
            </div>
          </form>
        </Card>
      )}

      <div className="flex gap-2 border-b border-slate-800" role="tablist" aria-label="Status">
        {(
          [
            ['OPEN', 'Open'],
            ['MERGED', 'Merged'],
            ['CLOSED', 'Closed'],
            [undefined, 'All'],
          ] as const
        ).map(([value, label]) => (
          <button
            key={label}
            type="button"
            role="tab"
            aria-selected={filter === value}
            onClick={() => setFilter(value)}
            className={`-mb-px border-b-2 px-4 py-2 text-sm font-medium transition ${
              filter === value
                ? 'border-indigo-400 text-white'
                : 'border-transparent text-slate-400 hover:text-slate-200'
            }`}
          >
            {label}
          </button>
        ))}
      </div>

      {list.isLoading && <LoadingState label="Loading pull requests…" />}
      {list.isError && (
        <ErrorState message={describeApiError(list.error)} onRetry={() => void list.refetch()} />
      )}
      {list.isSuccess && list.data.length === 0 && (
        <EmptyState
          icon={<GitPullRequest className="h-6 w-6" aria-hidden="true" />}
          title="No pull requests here"
          description="A pull request proposes merging one branch into another, so it can be reviewed first."
        />
      )}
      {list.isSuccess && list.data.length > 0 && (
        <Card className="divide-y divide-slate-800 p-0">
          <ul>
            {list.data.map((pr) => (
              <li key={pr.id}>
                <Link
                  to={`${repositoryPath}/pull-requests/${pr.number}`}
                  className="flex items-center gap-3 px-5 py-3 transition hover:bg-slate-800/60"
                >
                  <span className="text-sm text-slate-400">#{pr.number}</span>
                  <span className="flex-1 truncate text-sm text-slate-100">{pr.title}</span>
                  <span className="font-mono text-xs text-slate-400">
                    {pr.sourceBranch} → {pr.targetBranch}
                  </span>
                  <Badge tone={STATUS_TONE[pr.status]}>{STATUS_LABEL[pr.status]}</Badge>
                </Link>
              </li>
            ))}
          </ul>
        </Card>
      )}
    </div>
  );
}

/** One pull request: what it changes, whether it can merge, and the merge and close actions. */
export function PullRequestDetailPage() {
  const { organizationId, projectId, repositoryId, repositoryPath } = useRepositoryParams();
  const number = Number(useParams().number);
  const queryClient = useQueryClient();
  const [actionError, setActionError] = useState<string | null>(null);

  const pr = useQuery({
    queryKey: ['pull-request', repositoryId, number],
    queryFn: async () =>
      (await pullRequestApi.get(organizationId, projectId, repositoryId, number)).data.data,
  });
  const diff = useQuery({
    queryKey: ['pull-request-diff', repositoryId, number, pr.data?.status, pr.data?.sourceHead],
    queryFn: async () =>
      (await pullRequestApi.diff(organizationId, projectId, repositoryId, number)).data.data,
    enabled: pr.isSuccess,
  });

  const refresh = () => {
    setActionError(null);
    void queryClient.invalidateQueries({ queryKey: ['pull-request', repositoryId, number] });
    void queryClient.invalidateQueries({ queryKey: ['pull-requests', repositoryId] });
    void queryClient.invalidateQueries({ queryKey: ['repository-commits'] });
    void queryClient.invalidateQueries({ queryKey: ['repository-tree'] });
  };

  const merge = useMutation({
    // The head shown on this page: if the branch moved since, the server refuses rather than
    // merging commits nobody has looked at.
    mutationFn: () =>
      pullRequestApi.merge(
        organizationId,
        projectId,
        repositoryId,
        number,
        pr.data?.sourceHead ?? '',
      ),
    onSuccess: refresh,
    onError: (err) => setActionError(describeApiError(err)),
  });
  const close = useMutation({
    mutationFn: () => pullRequestApi.close(organizationId, projectId, repositoryId, number),
    onSuccess: refresh,
    onError: (err) => setActionError(describeApiError(err)),
  });

  const me = useAuthStore((state) => state.user?.id);
  const approve = useMutation({
    mutationFn: (withdraw: boolean) =>
      withdraw
        ? pullRequestApi.withdrawApproval(organizationId, projectId, repositoryId, number)
        : pullRequestApi.approve(organizationId, projectId, repositoryId, number),
    onSuccess: refresh,
    onError: (err) => setActionError(describeApiError(err)),
  });

  const [commentBody, setCommentBody] = useState('');
  const [commentError, setCommentError] = useState<string | null>(null);
  const comments = useQuery({
    queryKey: ['pull-request-comments', repositoryId, number],
    queryFn: async () =>
      (await pullRequestApi.comments(organizationId, projectId, repositoryId, number)).data.data,
  });
  const refreshComments = () =>
    void queryClient.invalidateQueries({
      queryKey: ['pull-request-comments', repositoryId, number],
    });
  const addComment = useMutation({
    mutationFn: () =>
      pullRequestApi.addComment(
        organizationId,
        projectId,
        repositoryId,
        number,
        commentBody.trim(),
      ),
    onSuccess: () => {
      setCommentBody('');
      setCommentError(null);
      refreshComments();
    },
    onError: (err) => setCommentError(describeApiError(err)),
  });
  const deleteComment = useMutation({
    mutationFn: (commentId: string) =>
      pullRequestApi.deleteComment(organizationId, projectId, repositoryId, number, commentId),
    onSuccess: refreshComments,
    onError: (err) => setCommentError(describeApiError(err)),
  });

  if (pr.isLoading) return <LoadingState label="Loading pull request…" />;
  if (pr.isError) {
    return <ErrorState message={describeApiError(pr.error)} onRetry={() => void pr.refetch()} />;
  }
  const data = pr.data as PullRequest;
  const conflicts = data.conflicts ?? [];
  const branchMissing = data.status === 'OPEN' && data.sourceHead === null;
  const canMerge = data.status === 'OPEN' && !branchMissing && conflicts.length === 0;
  const required = data.requiredApprovals ?? 0;
  const current = data.currentApprovals ?? 0;
  const approvalsMet = current >= required;
  const isAuthor = me === data.authorId;
  const myApproval = (data.approvals ?? []).find((approval) => approval.userId === me);

  return (
    <div className="space-y-6">
      <Link
        to={`${repositoryPath}/pull-requests`}
        className="inline-flex items-center gap-2 text-sm text-slate-400 underline-offset-4 hover:text-slate-200 hover:underline"
      >
        <ArrowLeft className="h-4 w-4" aria-hidden="true" />
        All pull requests
      </Link>

      <header className="space-y-2">
        <h1 className="text-2xl font-semibold text-white">
          {data.title} <span className="text-slate-400">#{data.number}</span>
        </h1>
        <div className="flex flex-wrap items-center gap-3 text-sm text-slate-400">
          <Badge tone={STATUS_TONE[data.status]}>{STATUS_LABEL[data.status]}</Badge>
          <span className="font-mono">
            {data.sourceBranch} → {data.targetBranch}
          </span>
        </div>
      </header>

      {data.description && (
        <Card>
          <p className="whitespace-pre-wrap text-sm text-slate-200">{data.description}</p>
        </Card>
      )}

      {data.status === 'OPEN' && (
        <Card className="space-y-4">
          {branchMissing && (
            <p className="flex items-center gap-2 text-sm text-amber-300">
              <AlertTriangle className="h-4 w-4" aria-hidden="true" />A branch of this pull request
              no longer exists, so it cannot be merged.
            </p>
          )}
          {!branchMissing && conflicts.length > 0 && (
            <div className="space-y-2">
              <p className="flex items-center gap-2 text-sm text-amber-300">
                <XCircle className="h-4 w-4" aria-hidden="true" />
                These files changed on both branches and must be reconciled before merging:
              </p>
              <ul
                aria-label="Conflicting files"
                className="list-inside list-disc font-mono text-sm text-slate-300"
              >
                {conflicts.map((path) => (
                  <li key={path}>{path}</li>
                ))}
              </ul>
            </div>
          )}
          {canMerge && (
            <p className="flex items-center gap-2 text-sm text-emerald-300">
              <CheckCircle2 className="h-4 w-4" aria-hidden="true" />
              No conflicts with {data.targetBranch}.
            </p>
          )}
          <div className="space-y-2 border-t border-slate-800 pt-4" aria-label="Approvals">
            <p className="text-sm text-slate-300">
              {required > 0
                ? `${current} of ${required} required ${required === 1 ? 'approval' : 'approvals'} of the current changes`
                : `${current} ${current === 1 ? 'approval' : 'approvals'} of the current changes`}
            </p>
            {(data.approvals ?? []).length > 0 && (
              <ul className="flex flex-wrap gap-2">
                {(data.approvals ?? []).map((approval) => (
                  <li key={approval.userId}>
                    <Badge tone={approval.current ? 'success' : 'neutral'}>
                      {approval.userName}
                      {approval.current ? '' : ' — outdated'}
                    </Badge>
                  </li>
                ))}
              </ul>
            )}
            {/* The author is not offered approval; the server refuses it too. */}
            {!isAuthor && (
              <Button
                size="sm"
                variant="secondary"
                loading={approve.isPending}
                onClick={() => approve.mutate(Boolean(myApproval?.current))}
              >
                {myApproval?.current
                  ? 'Withdraw approval'
                  : myApproval
                    ? 'Approve the new changes'
                    : 'Approve'}
              </Button>
            )}
          </div>
          {canMerge && !approvalsMet && (
            <p className="text-sm text-amber-300">
              Needs {required - current} more {required - current === 1 ? 'approval' : 'approvals'}{' '}
              before it can merge.
            </p>
          )}
          {actionError && (
            <p
              role="alert"
              className="rounded-xl border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-300"
            >
              {actionError}
            </p>
          )}
          <div className="flex gap-3">
            <Button
              leftIcon={<GitMerge className="h-4 w-4" />}
              onClick={() => merge.mutate()}
              loading={merge.isPending}
              disabled={!canMerge || !approvalsMet}
            >
              Merge
            </Button>
            <Button variant="secondary" onClick={() => close.mutate()} loading={close.isPending}>
              Close without merging
            </Button>
          </div>
        </Card>
      )}

      {data.status === 'MERGED' && data.mergeCommitId && (
        <p className="text-sm text-slate-400">
          Merged as{' '}
          <span className="font-mono text-slate-300">{data.mergeCommitId.slice(0, 7)}</span>.
        </p>
      )}

      <section aria-labelledby="changed-files" className="space-y-3">
        <h2 id="changed-files" className="text-lg font-semibold text-white">
          Changed files
        </h2>
        {diff.isLoading && <LoadingState label="Loading changes…" />}
        {diff.isError && (
          <ErrorState message={describeApiError(diff.error)} onRetry={() => void diff.refetch()} />
        )}
        {diff.isSuccess && (
          <Card className="divide-y divide-slate-800 p-0">
            <ul>
              {diff.data.entries.map((entry) => (
                <li
                  key={`${entry.oldPath}:${entry.newPath}`}
                  className="flex items-center gap-3 px-5 py-2.5"
                >
                  <Badge tone="neutral">{entry.changeType.toLowerCase()}</Badge>
                  <span className="flex-1 truncate font-mono text-sm text-slate-200">
                    {entry.newPath ?? entry.oldPath}
                  </span>
                  <span className="text-xs text-emerald-400">+{entry.linesAdded}</span>
                  <span className="text-xs text-red-400">−{entry.linesDeleted}</span>
                </li>
              ))}
            </ul>
          </Card>
        )}
      </section>

      <section aria-labelledby="discussion" className="space-y-3">
        <h2 id="discussion" className="text-lg font-semibold text-white">
          Discussion
        </h2>
        {comments.isLoading && <LoadingState label="Loading comments…" />}
        {comments.isError && (
          <ErrorState
            message={describeApiError(comments.error)}
            onRetry={() => void comments.refetch()}
          />
        )}
        {comments.isSuccess && comments.data.length === 0 && (
          <p className="text-sm text-slate-400">No comments yet.</p>
        )}
        {comments.isSuccess && comments.data.length > 0 && (
          <ul aria-label="Comments" className="space-y-3">
            {comments.data.map((comment) => (
              <li key={comment.id}>
                <Card className="space-y-2">
                  <div className="flex items-center justify-between gap-3 text-xs text-slate-400">
                    <span>
                      <span className="font-medium text-slate-300">{comment.authorName}</span> ·{' '}
                      {new Date(comment.createdAt).toLocaleString()}
                    </span>
                    {comment.authorId === me && (
                      <Button
                        size="sm"
                        variant="ghost"
                        aria-label="Delete comment"
                        onClick={() => deleteComment.mutate(comment.id)}
                      >
                        Delete
                      </Button>
                    )}
                  </div>
                  <p className="whitespace-pre-wrap text-sm text-slate-200">{comment.body}</p>
                </Card>
              </li>
            ))}
          </ul>
        )}
        <form
          aria-label="Add a comment"
          className="space-y-3"
          onSubmit={(event) => {
            event.preventDefault();
            if (commentBody.trim()) addComment.mutate();
          }}
        >
          <label htmlFor="pr-comment" className="sr-only">
            Comment
          </label>
          <textarea
            id="pr-comment"
            value={commentBody}
            onChange={(event) => setCommentBody(event.target.value)}
            rows={3}
            placeholder="Leave a comment"
            className="w-full rounded-xl border border-slate-700 bg-slate-800 px-4 py-3 text-sm text-slate-100"
          />
          {commentError && (
            <p
              role="alert"
              className="rounded-xl border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-300"
            >
              {commentError}
            </p>
          )}
          <Button type="submit" loading={addComment.isPending} disabled={!commentBody.trim()}>
            Comment
          </Button>
        </form>
      </section>
    </div>
  );
}
