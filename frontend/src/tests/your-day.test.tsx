import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { YourDay } from '@/components/dashboard/YourDay';

const list = vi.fn();

vi.mock('@/api/notification.api', async (original) => ({
  ...(await original<typeof import('@/api/notification.api')>()),
  notificationApi: { list: (...a: unknown[]) => list(...a) },
}));

const page = <T,>(content: T[]) => ({ data: { data: { content } } });

function renderIt() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <YourDay />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('YourDay', () => {
  beforeEach(() => vi.clearAllMocks());

  it('lists the latest notifications, linking where they point and marking the unread', async () => {
    list.mockResolvedValue(
      page([
        {
          id: 'n1',
          category: 'TASK',
          title: 'You were assigned "Fix login"',
          body: 'Platform board',
          link: '/organizations/o/projects/p',
          read: false,
          createdAt: new Date().toISOString(),
        },
        {
          id: 'n2',
          category: 'SECURITY',
          title: 'New sign-in from Firefox',
          body: null,
          link: null,
          read: true,
          createdAt: new Date().toISOString(),
        },
      ]),
    );
    renderIt();

    const assigned = await screen.findByRole('link', { name: /Fix login/ });
    expect(assigned).toHaveAttribute('href', '/organizations/o/projects/p');
    expect(assigned).toHaveTextContent('(unread)');
    expect(screen.getByText('New sign-in from Firefox').closest('a')).toBeNull();
    expect(list).toHaveBeenCalledWith(false, 0, 6);
  });

  it('says plainly when nothing needs the viewer', async () => {
    list.mockResolvedValue(page([]));
    renderIt();

    expect(await screen.findByText('Nothing needs you right now')).toBeInTheDocument();
  });
});
