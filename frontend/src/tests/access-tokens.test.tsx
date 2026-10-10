import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AxiosError, AxiosHeaders } from 'axios';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { AccessTokenSummary } from '@/api/auth.api';
import { AccessTokensSection } from '@/components/settings/AccessTokensSection';

const list = vi.fn();
const create = vi.fn();
const revoke = vi.fn();

vi.mock('@/api/auth.api', () => ({
  authApi: {
    listAccessTokens: (...a: unknown[]) => list(...a),
    createAccessToken: (...a: unknown[]) => create(...a),
    revokeAccessToken: (...a: unknown[]) => revoke(...a),
  },
}));

const env = <T,>(data: T) => ({ data: { data } });

function token(o: Partial<AccessTokenSummary> = {}): AccessTokenSummary {
  return {
    id: 't1',
    name: 'laptop',
    prefix: 'dfp_abcdefgh',
    createdAt: '2026-10-01T10:00:00Z',
    expiresAt: '2099-01-01T00:00:00Z',
    lastUsedAt: null,
    ...o,
  };
}

function renderSection() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={qc}>
      <AccessTokensSection />
    </QueryClientProvider>,
  );
}

describe('AccessTokensSection', () => {
  beforeEach(() => vi.clearAllMocks());

  it('shows a new token once, with the name and lifetime that were asked for', async () => {
    list.mockResolvedValue(env([]));
    create.mockResolvedValue(
      env({ details: token({ name: 'ci' }), token: 'dfp_secret-value-shown-once' }),
    );
    renderSection();

    await screen.findByText('No access tokens');
    await userEvent.type(screen.getByLabelText('Token name'), 'ci');
    await userEvent.selectOptions(screen.getByLabelText('Expires after'), '30');
    await userEvent.click(screen.getByRole('button', { name: 'Create token' }));

    expect(create).toHaveBeenCalledWith('ci', 30);
    expect(await screen.findByLabelText('New token')).toHaveTextContent(
      'dfp_secret-value-shown-once',
    );
    expect(screen.getByRole('status')).toHaveTextContent('will not be shown again');

    await userEvent.click(screen.getByRole('button', { name: 'Done' }));
    expect(screen.queryByText('dfp_secret-value-shown-once')).not.toBeInTheDocument();
  });

  it('lists tokens by prefix only, and revokes one', async () => {
    list.mockResolvedValue(
      env([token(), token({ id: 't2', name: 'old', prefix: 'dfp_zzzzzzzz' })]),
    );
    revoke.mockResolvedValue(env(null));
    renderSection();

    const items = await screen.findAllByRole('listitem');
    expect(items).toHaveLength(2);
    expect(within(items[0]).getByText('dfp_abcdefgh…')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Revoke old' }));
    await waitFor(() => expect(revoke).toHaveBeenCalledWith('t2'));
  });

  it('says why a token could not be created', async () => {
    list.mockResolvedValue(env([]));
    const refused = new AxiosError('400');
    refused.response = {
      status: 400,
      statusText: 'Bad Request',
      headers: {},
      config: { headers: new AxiosHeaders() },
      data: { message: 'At most 50 tokens may be active; revoke one first' },
    };
    create.mockRejectedValue(refused);
    renderSection();

    await userEvent.type(await screen.findByLabelText('Token name'), 'one too many');
    await userEvent.click(screen.getByRole('button', { name: 'Create token' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/revoke one first/);
  });

  it('cannot create a token without a name', async () => {
    list.mockResolvedValue(env([]));
    renderSection();
    expect(await screen.findByRole('button', { name: 'Create token' })).toBeDisabled();
  });
});
