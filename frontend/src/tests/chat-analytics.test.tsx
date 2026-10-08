import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { ChatMessage, ProjectActivity } from '@/api/chat.api';
import { AnalyticsPage } from '@/pages/AnalyticsPage';
import { ChatPage } from '@/pages/ChatPage';
import { useAuthStore } from '@/stores/authStore';

const list = vi.fn();
const post = vi.fn();
const remove = vi.fn();
const activity = vi.fn();

vi.mock('@/api/chat.api', () => ({
  chatApi: {
    list: (...a: unknown[]) => list(...a),
    post: (...a: unknown[]) => post(...a),
    remove: (...a: unknown[]) => remove(...a),
  },
  analyticsApi: { activity: (...a: unknown[]) => activity(...a) },
}));

const env = <T,>(data: T) => ({ data: { data } });

function msg(o: Partial<ChatMessage>): ChatMessage {
  return {
    id: 'm1',
    authorId: 'me',
    authorName: 'ada',
    body: 'hello',
    deleted: false,
    edited: false,
    createdAt: new Date().toISOString(),
    ...o,
  };
}

function renderAt(path: string, element: ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <MemoryRouter initialEntries={[`/organizations/o/projects/p/${path}`]}>
      <QueryClientProvider client={qc}>
        <Routes>
          <Route
            path={`/organizations/:organizationId/projects/:projectId/${path}`}
            element={element}
          />
        </Routes>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe('ChatPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAuthStore.setState({ user: { id: 'me' } as never });
  });

  it('shows messages oldest first', async () => {
    // The server returns newest first.
    list.mockResolvedValue(
      env([msg({ id: '2', body: 'second' }), msg({ id: '1', body: 'first' })]),
    );
    renderAt('chat', <ChatPage />);

    const items = await screen.findAllByRole('listitem');
    expect(items[0]).toHaveTextContent('first');
    expect(items[1]).toHaveTextContent('second');
  });

  it('says plainly that delivery is not real-time', async () => {
    list.mockResolvedValue(env([]));
    renderAt('chat', <ChatPage />);
    expect(await screen.findByText(/not instant/i)).toBeInTheDocument();
  });

  it('sends a message', async () => {
    list.mockResolvedValue(env([]));
    post.mockResolvedValue(env(msg({})));
    renderAt('chat', <ChatPage />);

    await screen.findByText('No messages yet');
    await userEvent.type(screen.getByLabelText('Message'), 'hi team');
    await userEvent.click(screen.getByRole('button', { name: 'Send' }));

    await waitFor(() => expect(post).toHaveBeenCalledWith('o', 'p', 'hi team'));
  });

  it('offers delete only on your own messages', async () => {
    list.mockResolvedValue(
      env([
        msg({ id: 'a', authorId: 'me', body: 'mine' }),
        msg({ id: 'b', authorId: 'someone-else', body: 'theirs' }),
      ]),
    );
    renderAt('chat', <ChatPage />);

    await screen.findByText('mine');
    expect(screen.getAllByRole('button', { name: /delete your message/i })).toHaveLength(1);
  });

  it('shows a deleted message without its text', async () => {
    list.mockResolvedValue(env([msg({ deleted: true, body: null })]));
    renderAt('chat', <ChatPage />);
    expect(await screen.findByText('Message deleted')).toBeInTheDocument();
  });

  it('renders message text as text, never as markup', async () => {
    list.mockResolvedValue(env([msg({ body: '<img src=x onerror="alert(1)">' })]));
    renderAt('chat', <ChatPage />);

    expect(await screen.findByText(/onerror/)).toBeInTheDocument();
    expect(document.querySelector('img')).toBeNull();
  });
});

describe('AnalyticsPage', () => {
  const data: ProjectActivity = {
    from: '2026-09-09',
    to: '2026-10-08',
    totalTasksCreated: 7,
    totalTasksCompleted: 3,
    totalTasksAssigned: 2,
    totalCommits: 11,
    days: [
      {
        day: '2026-10-08',
        tasksCreated: 7,
        tasksCompleted: 3,
        tasksAssigned: 2,
        commits: 11,
        repositoriesCreated: 0,
      },
    ],
    completeness: 'Counted from domain events as they were published.',
  };

  it('shows the totals the server returned', async () => {
    activity.mockResolvedValue(env(data));
    renderAt('analytics', <AnalyticsPage />);

    // Scoped to each card: the same numbers also appear in the screen-reader table.
    // [0] is the card; the same labels also head the screen-reader table.
    expect((await screen.findAllByText('Tasks created'))[0].parentElement).toHaveTextContent('7');
    expect(screen.getAllByText('Commits')[0].parentElement).toHaveTextContent('11');
  });

  it("shows the server's completeness note, so zero is not misread", async () => {
    activity.mockResolvedValue(env(data));
    renderAt('analytics', <AnalyticsPage />);
    expect(await screen.findByText(/Counted from domain events/)).toBeInTheDocument();
  });

  it('offers a retry when loading fails', async () => {
    activity.mockRejectedValue(new Error('down'));
    renderAt('analytics', <AnalyticsPage />);
    expect(await screen.findByRole('button', { name: /try again|retry/i })).toBeInTheDocument();
  });
});
