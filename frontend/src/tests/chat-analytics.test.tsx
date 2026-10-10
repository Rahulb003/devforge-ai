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
const audit = vi.fn();
const verifyAudit = vi.fn();

// The stream is a network concern; here the page is tested against each status it can report.
const streamStatus = vi.fn(() => 'live');
vi.mock('@/lib/chatStream', () => ({ useChatStream: () => streamStatus() }));

vi.mock('@/api/chat.api', () => ({
  chatApi: {
    list: (...a: unknown[]) => list(...a),
    post: (...a: unknown[]) => post(...a),
    remove: (...a: unknown[]) => remove(...a),
  },
  analyticsApi: {
    activity: (...a: unknown[]) => activity(...a),
    audit: (...a: unknown[]) => audit(...a),
    verifyAudit: (...a: unknown[]) => verifyAudit(...a),
  },
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

  it('says when it is live', async () => {
    list.mockResolvedValue(env([]));
    renderAt('chat', <ChatPage />);
    expect(await screen.findByText(/new messages appear as they are sent/i)).toBeInTheDocument();
  });

  it('says when it is reconnecting, so late messages are not a surprise', async () => {
    streamStatus.mockReturnValueOnce('reconnecting');
    list.mockResolvedValue(env([]));
    renderAt('chat', <ChatPage />);
    expect(await screen.findByText(/reconnecting/i)).toBeInTheDocument();
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

  describe('audit integrity', () => {
    beforeEach(() => {
      activity.mockResolvedValue(env(data));
      audit.mockResolvedValue(
        env({
          content: [
            {
              eventId: 'e1',
              eventType: 'ProjectCreated',
              source: 'project-service',
              actorId: 'me',
              occurredAt: new Date().toISOString(),
              details: { name: 'Demo' },
            },
          ],
          totalElements: 1,
          number: 0,
          totalPages: 1,
        }),
      );
    });

    it('reports an intact chain with the hash to keep', async () => {
      verifyAudit.mockResolvedValue(
        env({
          intact: true,
          entries: 3,
          unchainedEntries: 0,
          brokenAtSequence: null,
          problem: null,
          headHash: 'ab'.repeat(32),
        }),
      );
      renderAt('analytics', <AnalyticsPage />);

      await userEvent.click(await screen.findByRole('button', { name: 'Verify integrity' }));
      const status = await screen.findByRole('status');
      expect(status).toHaveTextContent('All 3 chained entries are intact');
      expect(status).toHaveTextContent('ab'.repeat(32));
    });

    it('says where the log was altered', async () => {
      verifyAudit.mockResolvedValue(
        env({
          intact: false,
          entries: 1,
          unchainedEntries: 0,
          brokenAtSequence: 2,
          problem: 'its content does not match its hash',
          headHash: null,
        }),
      );
      renderAt('analytics', <AnalyticsPage />);

      await userEvent.click(await screen.findByRole('button', { name: 'Verify integrity' }));
      expect(await screen.findByRole('alert')).toHaveTextContent(
        'altered at entry 2: its content does not match its hash',
      );
    });
  });

  it('offers a retry when loading fails', async () => {
    activity.mockRejectedValue(new Error('down'));
    renderAt('analytics', <AnalyticsPage />);
    expect(await screen.findByRole('button', { name: /try again|retry/i })).toBeInTheDocument();
  });
});
