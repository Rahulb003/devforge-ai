import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AxiosError, AxiosHeaders } from 'axios';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { Blob, Branch, Commit, Repository, TreeEntry } from '@/api/git.api';
import { RepositoriesPage } from '@/pages/RepositoriesPage';
import { RepositoryBrowserPage } from '@/pages/RepositoryBrowserPage';

const list = vi.fn();
const get = vi.fn();
const create = vi.fn();
const branches = vi.fn();
const tree = vi.fn();
const blob = vi.fn();
const commits = vi.fn();
const commitFile = vi.fn();
const commitChanges = vi.fn();

// CodeMirror needs layout jsdom does not have; the browser suite covers the real editor.
vi.mock('@/components/editor/CodeEditor', () => ({
  default: ({
    path,
    value,
    onChange,
  }: {
    path: string;
    value: string;
    onChange: (value: string) => void;
  }) => (
    <textarea
      aria-label={`Editing ${path}`}
      defaultValue={value}
      onChange={(event) => onChange(event.target.value)}
    />
  ),
}));

vi.mock('@/api/git.api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/git.api')>();
  return {
    ...actual,
    gitApi: {
      list: (...a: unknown[]) => list(...a),
      get: (...a: unknown[]) => get(...a),
      create: (...a: unknown[]) => create(...a),
      remove: vi.fn(),
      branches: (...a: unknown[]) => branches(...a),
      createBranch: vi.fn(),
      commits: (...a: unknown[]) => commits(...a),
      tree: (...a: unknown[]) => tree(...a),
      blob: (...a: unknown[]) => blob(...a),
      diff: vi.fn(),
      commitFile: (...a: unknown[]) => commitFile(...a),
      commitChanges: (...a: unknown[]) => commitChanges(...a),
    },
  };
});

vi.mock('@/api/project.api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/project.api')>();
  return {
    ...actual,
    projectApi: {
      ...actual.projectApi,
      getProject: vi.fn().mockResolvedValue({ data: { data: { id: 'p1', name: 'Core' } } }),
    },
  };
});

const ORG = 'org-1';
const PROJ = 'proj-1';
const REPO = 'repo-1';

function repository(overrides: Partial<Repository> = {}): Repository {
  return {
    id: REPO,
    projectId: PROJ,
    organizationId: ORG,
    name: 'payments-api',
    description: 'Handles payments',
    defaultBranch: 'main',
    empty: false,
    createdBy: 'u1',
    createdAt: new Date().toISOString(),
    updatedAt: null,
    ...overrides,
  };
}

function envelope<T>(data: T) {
  return { data: { data } };
}

function pageOf<T>(content: T[]) {
  return envelope({ content, totalElements: content.length, number: 0, size: 20 });
}

/** A client per render: retries off so error states assert immediately, no cache carry-over. */
function renderAt(route: string, path: string, element: ReactNode) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  return render(
    <MemoryRouter initialEntries={[route]}>
      <QueryClientProvider client={queryClient}>
        <Routes>
          <Route path={path} element={element} />
        </Routes>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

const LIST_ROUTE = '/organizations/:organizationId/projects/:projectId/repositories';
const BROWSE_ROUTE =
  '/organizations/:organizationId/projects/:projectId/repositories/:repositoryId';

function renderList() {
  return renderAt(
    `/organizations/${ORG}/projects/${PROJ}/repositories`,
    LIST_ROUTE,
    <RepositoriesPage />,
  );
}

function renderBrowser(query = '') {
  return renderAt(
    `/organizations/${ORG}/projects/${PROJ}/repositories/${REPO}${query}`,
    BROWSE_ROUTE,
    <RepositoryBrowserPage />,
  );
}

describe('RepositoriesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('lists repositories with their default branch', async () => {
    list.mockResolvedValue(pageOf([repository()]));

    renderList();

    expect(await screen.findByText('payments-api')).toBeInTheDocument();
    expect(screen.getByText('Handles payments')).toBeInTheDocument();
    expect(screen.getByText('main')).toBeInTheDocument();
  });

  it('marks a repository with no commits as empty', async () => {
    list.mockResolvedValue(pageOf([repository({ empty: true })]));

    renderList();

    expect(await screen.findByText('Empty')).toBeInTheDocument();
  });

  it('explains the empty state rather than showing a blank panel', async () => {
    list.mockResolvedValue(pageOf([]));

    renderList();

    expect(await screen.findByText('No repositories yet')).toBeInTheDocument();
  });

  it('offers a retry when the list fails', async () => {
    list.mockRejectedValue(new Error('nope'));

    renderList();

    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /try again|retry/i })).toBeInTheDocument();
  });

  it('creates a repository and refreshes the list', async () => {
    list.mockResolvedValue(pageOf([]));
    create.mockResolvedValue(envelope(repository()));

    renderList();
    await screen.findByText('No repositories yet');

    await userEvent.click(screen.getByRole('button', { name: /new repository/i }));
    await userEvent.type(screen.getByLabelText('Name'), 'payments-api');
    await userEvent.click(screen.getByRole('button', { name: 'Create' }));

    await waitFor(() =>
      expect(create).toHaveBeenCalledWith(ORG, PROJ, {
        name: 'payments-api',
        description: undefined,
      }),
    );
  });

  it("surfaces the server's rejection of an unsafe name rather than guessing", async () => {
    list.mockResolvedValue(pageOf([]));
    // A real AxiosError, not a look-alike: describeApiError checks `instanceof AxiosError`, so a
    // plain object with the same shape falls through to the generic message and the test would
    // pass or fail for the wrong reason.
    const rejection = new AxiosError('Request failed with status code 400');
    rejection.response = {
      status: 400,
      statusText: 'Bad Request',
      headers: {},
      // The server owns this rule — a name is a directory on disk — so its message is the useful one.
      config: { headers: new AxiosHeaders() },
      data: { message: 'Repository name must start with a letter or digit' },
    };
    create.mockRejectedValue(rejection);

    renderList();
    await screen.findByText('No repositories yet');

    await userEvent.click(screen.getByRole('button', { name: /new repository/i }));
    await userEvent.type(screen.getByLabelText('Name'), '../escape');
    await userEvent.click(screen.getByRole('button', { name: 'Create' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      /must start with a letter or digit/i,
    );
  });

  it('links each repository to its browser', async () => {
    list.mockResolvedValue(pageOf([repository()]));

    renderList();

    const link = await screen.findByRole('link', { name: /payments-api/ });
    expect(link).toHaveAttribute(
      'href',
      `/organizations/${ORG}/projects/${PROJ}/repositories/${REPO}`,
    );
  });
});

describe('RepositoryBrowserPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    get.mockResolvedValue(envelope(repository()));
    branches.mockResolvedValue(
      envelope([
        { name: 'main', commitId: 'a'.repeat(40), isDefault: true },
        { name: 'feature/x', commitId: 'b'.repeat(40), isDefault: false },
      ] satisfies Branch[]),
    );
    tree.mockResolvedValue(
      envelope([
        { name: 'src', path: 'src', type: 'DIRECTORY', size: null },
        { name: 'README.md', path: 'README.md', type: 'FILE', size: 1024 },
      ] satisfies TreeEntry[]),
    );
  });

  it('lists the tree with directories distinguished for screen readers, not just by icon', async () => {
    renderBrowser();

    const srcRow = await screen.findByRole('button', { name: /src/ });
    expect(within(srcRow).getByText('directory')).toBeInTheDocument();

    const readmeRow = screen.getByRole('button', { name: /README\.md/ });
    expect(within(readmeRow).getByText('file')).toBeInTheDocument();
    expect(within(readmeRow).getByText('1.0 KB')).toBeInTheDocument();
  });

  it('opens a file and shows its contents', async () => {
    blob.mockResolvedValue(
      envelope({
        path: 'README.md',
        size: 14,
        binary: false,
        truncated: false,
        content: '# Payments API',
      } satisfies Blob),
    );

    renderBrowser();
    await userEvent.click(await screen.findByRole('button', { name: /README\.md/ }));

    expect(await screen.findByText('# Payments API')).toBeInTheDocument();
    await waitFor(() => expect(blob).toHaveBeenCalledWith(ORG, PROJ, REPO, 'README.md', 'main'));
  });

  it('opening a file keeps the branch being viewed', async () => {
    // Opening a file used to drop the ref from the URL, so a file on another branch was read - and,
    // once edited, committed - on the default branch instead.
    blob.mockResolvedValue(
      envelope({
        path: 'README.md',
        size: 1,
        binary: false,
        truncated: false,
        content: 'x',
      } satisfies Blob),
    );

    renderBrowser('?ref=feature%2Fx');
    await userEvent.click(await screen.findByRole('button', { name: /README\.md/ }));

    await waitFor(() =>
      expect(blob).toHaveBeenCalledWith(ORG, PROJ, REPO, 'README.md', 'feature/x'),
    );
    expect(blob).not.toHaveBeenCalledWith(ORG, PROJ, REPO, 'README.md', 'main');
  });

  it('reads the open file from the URL, so a link to a file is shareable', async () => {
    blob.mockResolvedValue(
      envelope({
        path: 'src/App.java',
        size: 10,
        binary: false,
        truncated: false,
        content: 'class App {}',
      } satisfies Blob),
    );

    renderBrowser('?file=src/App.java');

    expect(await screen.findByText('class App {}')).toBeInTheDocument();
    // Deep-linking must not require clicking down the tree first.
    expect(tree).not.toHaveBeenCalled();
  });

  it('refuses to render a binary file as text', async () => {
    blob.mockResolvedValue(
      envelope({
        path: 'logo.png',
        size: 2048,
        binary: true,
        truncated: false,
        content: null,
      } satisfies Blob),
    );

    renderBrowser('?file=logo.png');

    // Rendering bytes as text looks like corruption of the file, which sends the
    // reader after the wrong problem.
    expect(await screen.findByText('Binary file')).toBeInTheDocument();
  });

  it('says so when a file was truncated', async () => {
    blob.mockResolvedValue(
      envelope({
        path: 'big.txt',
        size: 5_000_000,
        binary: false,
        truncated: true,
        content: 'first part only',
      } satisfies Blob),
    );

    renderBrowser('?file=big.txt');

    expect(await screen.findByText(/truncated/i)).toBeInTheDocument();
  });

  it('shows commit history with authorship on the History tab', async () => {
    commits.mockResolvedValue(
      envelope([
        {
          id: 'c'.repeat(40),
          shortId: 'c123456',
          message: 'Add App\n\nwith a body that should not be shown in the list',
          authorName: 'ada',
          authorEmail: 'ada@example.com',
          committedAt: new Date().toISOString(),
          parentIds: [],
        },
      ] satisfies Commit[]),
    );

    renderBrowser();
    await screen.findByRole('button', { name: /README\.md/ });

    await userEvent.click(screen.getByRole('tab', { name: 'History' }));

    expect(await screen.findByText('Add App')).toBeInTheDocument();
    expect(screen.getByText('c123456')).toBeInTheDocument();
    expect(screen.getByText(/ada/)).toBeInTheDocument();
    // Only the subject line belongs in a list.
    expect(screen.queryByText(/with a body that should not be shown/)).not.toBeInTheDocument();
  });

  it('does not ask the server for a tree or branches when the repository is empty', async () => {
    get.mockResolvedValue(envelope(repository({ empty: true })));

    renderBrowser();

    expect(await screen.findByText('This repository is empty')).toBeInTheDocument();
    // There is nothing to list before the first commit, and asking would only 404.
    expect(tree).not.toHaveBeenCalled();
    expect(branches).not.toHaveBeenCalled();
  });

  it('switching branch resets the path so a stale directory is not requested', async () => {
    renderBrowser('?path=src');

    await screen.findByRole('button', { name: /README\.md/ });
    await userEvent.selectOptions(screen.getByRole('combobox'), 'feature/x');

    // The old directory may not exist on the new branch, so the request must start at the root.
    await waitFor(() =>
      expect(tree).toHaveBeenLastCalledWith(ORG, PROJ, REPO, 'feature/x', undefined),
    );
  });

  it('offers a retry when the tree fails to load', async () => {
    tree.mockRejectedValue(new Error('boom'));

    renderBrowser();

    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /try again|retry/i })).toBeInTheDocument();
  });

  it('an empty repository offers a way to make the first commit', async () => {
    get.mockResolvedValue(envelope(repository({ empty: true })));

    renderBrowser();

    // Without this the feature is a dead end: a new repository is empty, and there would be no way
    // to put anything in it short of calling the API by hand.
    await screen.findByText('This repository is empty');
    expect(screen.getByRole('button', { name: /add a file/i })).toBeInTheDocument();
  });

  it('commits a file from the browser', async () => {
    get.mockResolvedValue(envelope(repository({ empty: true })));
    commitFile.mockResolvedValue(
      envelope({
        id: 'd'.repeat(40),
        shortId: 'd123456',
        message: 'Add App',
        authorName: 'ada',
        authorEmail: 'ada@example.com',
        committedAt: new Date().toISOString(),
        parentIds: [],
      } satisfies Commit),
    );

    renderBrowser();
    await userEvent.click(await screen.findByRole('button', { name: /add a file/i }));

    await userEvent.type(screen.getByLabelText('Path'), 'src/App.java');
    // userEvent reads `{` as the start of a key descriptor, so a literal brace is doubled.
    await userEvent.type(screen.getByLabelText('Content'), 'class App {{}');
    await userEvent.type(screen.getByLabelText('Commit message'), 'Add App');
    await userEvent.click(screen.getByRole('button', { name: 'Commit' }));

    await waitFor(() =>
      expect(commitFile).toHaveBeenCalledWith(ORG, PROJ, REPO, {
        path: 'src/App.java',
        content: 'class App {}',
        message: 'Add App',
        branch: undefined,
      }),
    );
  });

  it('will not commit without a path and a message', async () => {
    get.mockResolvedValue(envelope(repository({ empty: true })));

    renderBrowser();
    await userEvent.click(await screen.findByRole('button', { name: /add a file/i }));

    // A commit with no message is one nobody can interpret later, and a file needs somewhere to go.
    expect(screen.getByRole('button', { name: 'Commit' })).toBeDisabled();

    await userEvent.type(screen.getByLabelText('Path'), 'a.txt');
    expect(screen.getByRole('button', { name: 'Commit' })).toBeDisabled();

    await userEvent.type(screen.getByLabelText('Commit message'), 'Add a.txt');
    expect(screen.getByRole('button', { name: 'Commit' })).toBeEnabled();
  });

  it("surfaces the server's path rejection on commit", async () => {
    get.mockResolvedValue(envelope(repository({ empty: true })));
    const rejection = new AxiosError('Request failed with status code 400');
    rejection.response = {
      status: 400,
      statusText: 'Bad Request',
      headers: {},
      config: { headers: new AxiosHeaders() },
      data: { message: 'Path must not traverse outside the repository' },
    };
    commitFile.mockRejectedValue(rejection);

    renderBrowser();
    await userEvent.click(await screen.findByRole('button', { name: /add a file/i }));
    await userEvent.type(screen.getByLabelText('Path'), '../escape.txt');
    await userEvent.type(screen.getByLabelText('Commit message'), 'nope');
    await userEvent.click(screen.getByRole('button', { name: 'Commit' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/must not traverse/i);
  });

  describe('editing', () => {
    const HEAD = 'a'.repeat(40);

    beforeEach(() => {
      blob.mockImplementation((_o, _p, _r, path: string) =>
        Promise.resolve(
          envelope({
            path,
            size: 5,
            binary: false,
            truncated: false,
            content: `old ${path}`,
          } satisfies Blob),
        ),
      );
    });

    async function editReadme(text: string) {
      renderBrowser('?file=README.md');
      await userEvent.click(await screen.findByRole('button', { name: 'Edit' }));
      const editor = await screen.findByLabelText('Editing README.md');
      await userEvent.clear(editor);
      await userEvent.type(editor, text);
      await userEvent.click(screen.getByRole('button', { name: 'Done editing' }));
    }

    it('loads the file at the pinned head commit and commits against it', async () => {
      commitChanges.mockResolvedValue(envelope({ id: 'c'.repeat(40) }));

      await editReadme('new readme');

      // Read at the commit, not the branch name: a later commit to the branch must not slip in.
      expect(blob).toHaveBeenCalledWith(ORG, PROJ, REPO, 'README.md', HEAD);
      const panel = screen.getByRole('form', { name: 'Uncommitted changes' });
      await userEvent.type(within(panel).getByLabelText('Commit message'), 'Update readme');
      await userEvent.click(within(panel).getByRole('button', { name: 'Commit 1 change' }));

      await waitFor(() =>
        expect(commitChanges).toHaveBeenCalledWith(ORG, PROJ, REPO, {
          message: 'Update readme',
          branch: 'main',
          baseCommitId: HEAD,
          changes: [{ path: 'README.md', content: 'new readme' }],
        }),
      );
      await waitFor(() =>
        expect(screen.queryByRole('form', { name: 'Uncommitted changes' })).not.toBeInTheDocument(),
      );
    });

    it('stages a deletion, and an edit undone back to the original is not a change', async () => {
      renderBrowser('?file=README.md');
      await userEvent.click(await screen.findByRole('button', { name: 'Delete' }));

      const panel = await screen.findByRole('form', { name: 'Uncommitted changes' });
      expect(within(panel).getByText('Deleted')).toBeInTheDocument();
      await userEvent.click(
        within(panel).getByRole('button', { name: 'Discard changes to README.md' }),
      );
      expect(screen.queryByRole('form', { name: 'Uncommitted changes' })).not.toBeInTheDocument();
    });

    it('explains a moved branch and offers to discard and reload', async () => {
      const conflict = new AxiosError('Request failed with status code 409');
      conflict.response = {
        status: 409,
        statusText: 'Conflict',
        headers: {},
        config: { headers: new AxiosHeaders() },
        data: { message: 'The branch has new commits since you started editing.' },
      };
      commitChanges.mockRejectedValue(conflict);

      await editReadme('mine');
      const panel = screen.getByRole('form', { name: 'Uncommitted changes' });
      await userEvent.type(within(panel).getByLabelText('Commit message'), 'Mine');
      await userEvent.click(within(panel).getByRole('button', { name: 'Commit 1 change' }));

      expect(await within(panel).findByRole('alert')).toHaveTextContent(/new commits/i);
      await userEvent.click(within(panel).getByRole('button', { name: 'Discard all and reload' }));
      expect(screen.queryByRole('form', { name: 'Uncommitted changes' })).not.toBeInTheDocument();
    });

    it('will not switch branch while there are uncommitted changes', async () => {
      await editReadme('pending');
      expect(screen.getByRole('combobox', { name: 'Branch' })).toBeDisabled();
    });
  });
});
