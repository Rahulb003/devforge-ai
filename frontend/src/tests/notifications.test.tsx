import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { Notification } from '@/api/notification.api';
import { NotificationBell } from '@/components/layout/NotificationBell';
import { NotificationsPage } from '@/pages/NotificationsPage';

const list = vi.fn();
const unreadCount = vi.fn();
const markRead = vi.fn();
const markAllRead = vi.fn();
const markUnread = vi.fn();
const remove = vi.fn();

vi.mock('@/api/notification.api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/notification.api')>();
  return {
    ...actual,
    notificationApi: {
      list: (...args: unknown[]) => list(...args),
      unreadCount: () => unreadCount(),
      markRead: (id: string) => markRead(id),
      markUnread: (id: string) => markUnread(id),
      markAllRead: () => markAllRead(),
      remove: (id: string) => remove(id),
    },
  };
});

function notification(overrides: Partial<Notification> = {}): Notification {
  return {
    id: 'n1',
    organizationId: 'org-1',
    category: 'TASK',
    eventType: 'TaskAssigned',
    title: 'You were assigned task #12',
    body: 'Harden the CORS configuration',
    link: '/projects/p1/tasks/t1',
    read: false,
    readAt: null,
    createdAt: new Date().toISOString(),
    ...overrides,
  };
}

function page(content: Notification[]) {
  return { data: { data: { content, totalElements: content.length, number: 0, size: 20 } } };
}

/**
 * A client per render, with retries off.
 *
 * Shared across tests, a cached result from one leaks into the next; with
 * retries on, a test asserting an error state waits out the backoff first.
 */
function renderWith(ui: ReactNode) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>
    </MemoryRouter>,
  );
}

describe('NotificationBell', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows the unread count in the accessible name, not only as a colour', async () => {
    unreadCount.mockResolvedValue({ data: { data: { unread: 3 } } });

    renderWith(<NotificationBell />);

    await waitFor(() =>
      expect(screen.getByRole('link', { name: /3 unread/i })).toBeInTheDocument(),
    );
  });

  it('renders no badge when nothing is unread', async () => {
    unreadCount.mockResolvedValue({ data: { data: { unread: 0 } } });

    renderWith(<NotificationBell />);

    await waitFor(() =>
      expect(screen.getByRole('link', { name: 'Notifications' })).toBeInTheDocument(),
    );
    expect(screen.queryByText('0')).not.toBeInTheDocument();
  });

  it('stays usable when the count cannot be loaded', async () => {
    // The count is peripheral. A failing notification service must not put an
    // error across every page of the app.
    unreadCount.mockRejectedValue(new Error('service unavailable'));

    renderWith(<NotificationBell />);

    await waitFor(() => expect(unreadCount).toHaveBeenCalled());
    expect(screen.getByRole('link', { name: 'Notifications' })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('caps a large count rather than stretching the badge', async () => {
    unreadCount.mockResolvedValue({ data: { data: { unread: 1234 } } });

    renderWith(<NotificationBell />);

    await waitFor(() => expect(screen.getByText('99+')).toBeInTheDocument());
    // The real number still reaches a screen reader.
    expect(screen.getByRole('link', { name: /1234 unread/i })).toBeInTheDocument();
  });
});

describe('NotificationsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    unreadCount.mockResolvedValue({ data: { data: { unread: 1 } } });
  });

  it('lists notifications with their body text', async () => {
    list.mockResolvedValue(page([notification()]));

    renderWith(<NotificationsPage />);

    expect(await screen.findByText('You were assigned task #12')).toBeInTheDocument();
    expect(screen.getByText('Harden the CORS configuration')).toBeInTheDocument();
    expect(screen.getByText('Unread')).toBeInTheDocument();
  });

  it('explains the empty state instead of showing a bare blank panel', async () => {
    list.mockResolvedValue(page([]));

    renderWith(<NotificationsPage />);

    expect(await screen.findByText('No notifications yet')).toBeInTheDocument();
  });

  it('offers a retry when loading fails', async () => {
    list.mockRejectedValue(new Error('nope'));

    renderWith(<NotificationsPage />);

    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /try again|retry/i })).toBeInTheDocument();
  });

  it('marks one notification read', async () => {
    list.mockResolvedValue(page([notification()]));
    markRead.mockResolvedValue({ data: { data: notification({ read: true }) } });

    renderWith(<NotificationsPage />);
    await screen.findByText('You were assigned task #12');

    await userEvent.click(screen.getByRole('button', { name: 'Mark read' }));

    await waitFor(() => expect(markRead).toHaveBeenCalledWith('n1'));
  });

  it('disables "mark all read" when everything is already read', async () => {
    list.mockResolvedValue(page([notification({ read: true, readAt: new Date().toISOString() })]));

    renderWith(<NotificationsPage />);
    await screen.findByText('You were assigned task #12');

    expect(screen.getByRole('button', { name: /mark all read/i })).toBeDisabled();
  });

  it('marks everything read', async () => {
    list.mockResolvedValue(page([notification()]));
    markAllRead.mockResolvedValue({ data: { data: { marked: 1 } } });

    renderWith(<NotificationsPage />);
    await screen.findByText('You were assigned task #12');

    await userEvent.click(screen.getByRole('button', { name: /mark all read/i }));

    await waitFor(() => expect(markAllRead).toHaveBeenCalled());
  });

  it('asks the server for unread only when the filter is toggled', async () => {
    list.mockResolvedValue(page([notification()]));

    renderWith(<NotificationsPage />);
    await screen.findByText('You were assigned task #12');

    await userEvent.click(screen.getByRole('button', { name: 'Unread only' }));

    // Filtering happens server-side: the unread set can be far larger than one page,
    // so filtering the current page in the browser would silently hide the rest.
    await waitFor(() => expect(list).toHaveBeenLastCalledWith(true));
  });

  it('names the delete control after the notification it removes', async () => {
    list.mockResolvedValue(page([notification()]));
    remove.mockResolvedValue({ data: { data: null } });

    renderWith(<NotificationsPage />);
    await screen.findByText('You were assigned task #12');

    // "Delete" alone, repeated down a list, tells a screen-reader user nothing
    // about which row they are about to act on.
    await userEvent.click(
      screen.getByRole('button', { name: 'Delete notification: You were assigned task #12' }),
    );

    await waitFor(() => expect(remove).toHaveBeenCalledWith('n1'));
  });
});
