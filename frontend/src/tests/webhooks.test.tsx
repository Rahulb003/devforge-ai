import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AxiosError, AxiosHeaders } from 'axios';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { Webhooks } from '@/components/repository/Webhooks';

const list = vi.fn();
const create = vi.fn();
const remove = vi.fn();

vi.mock('@/api/git.api', () => ({
  webhookApi: {
    list: (...a: unknown[]) => list(...a),
    create: (...a: unknown[]) => create(...a),
    remove: (...a: unknown[]) => remove(...a),
  },
}));

const env = <T,>(data: T) => ({ data: { data } });
const hook = {
  id: 'w1',
  url: 'https://ci.example.com/hook',
  createdAt: '2026-10-10T00:00:00Z',
  lastStatus: 204,
  lastError: null,
  lastDeliveredAt: '2026-10-10T00:01:00Z',
};

function renderIt() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <Webhooks organizationId="o" projectId="p" repositoryId="r" />
    </QueryClientProvider>,
  );
}

describe('Webhooks', () => {
  beforeEach(() => vi.clearAllMocks());

  it('fetches nothing until opened', async () => {
    renderIt();
    expect(screen.getByRole('button', { name: 'Webhooks' })).toBeInTheDocument();
    expect(list).not.toHaveBeenCalled();
  });

  it('lists deliveries and shows a new secret once', async () => {
    list.mockResolvedValue(env([hook]));
    create.mockResolvedValue(
      env({ webhook: { ...hook, id: 'w2', lastDeliveredAt: null }, secret: 'whsec_abc' }),
    );
    renderIt();
    await userEvent.click(screen.getByRole('button', { name: 'Webhooks' }));

    expect(await screen.findByText('Last delivery answered 204')).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Payload URL'), 'https://other.example.com/h');
    await userEvent.click(screen.getByRole('button', { name: 'Add webhook' }));

    expect(create).toHaveBeenCalledWith('o', 'p', 'r', 'https://other.example.com/h');
    expect(await screen.findByRole('status')).toHaveTextContent('whsec_abc');
  });

  it('tells a non-admin who manages webhooks rather than offering a form', async () => {
    list.mockRejectedValue(
      new AxiosError('Forbidden', '403', undefined, undefined, {
        status: 403,
        statusText: 'Forbidden',
        data: {},
        headers: {},
        config: { headers: new AxiosHeaders() },
      }),
    );
    renderIt();
    await userEvent.click(screen.getByRole('button', { name: 'Webhooks' }));

    expect(await screen.findByText('Only project admins manage webhooks.')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByLabelText('Payload URL')).not.toBeInTheDocument());
  });
});
