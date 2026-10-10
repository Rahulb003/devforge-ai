import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AxiosError, AxiosHeaders } from 'axios';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { UserProfile } from '@/api/auth.api';
import { DeleteAccountSection } from '@/components/settings/DeleteAccountSection';
import { useAuthStore } from '@/stores/authStore';

const deleteAccount = vi.fn();
const mfaStatus = vi.fn();

vi.mock('@/api/auth.api', () => ({
  authApi: {
    deleteAccount: (...a: unknown[]) => deleteAccount(...a),
    mfaStatus: (...a: unknown[]) => mfaStatus(...a),
  },
}));

const env = <T,>(data: T) => ({ data: { data } });

function renderIt() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/settings']}>
        <Routes>
          <Route path="/settings" element={<DeleteAccountSection />} />
          <Route path="/login" element={<p>login page</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('DeleteAccountSection', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAuthStore.setState({ user: { username: 'ada' } as unknown as UserProfile });
    mfaStatus.mockResolvedValue(env({ enabled: false, remainingBackupCodes: 0 }));
  });

  it('stays disabled until the username is typed, then deletes and signs out', async () => {
    deleteAccount.mockResolvedValue(env(null));
    renderIt();
    await userEvent.click(screen.getByRole('button', { name: 'Delete my account' }));

    const submit = screen.getByRole('button', { name: 'Delete account permanently' });
    await userEvent.type(screen.getByLabelText('Password'), 'Str0ng-Passw0rd!');
    await userEvent.type(screen.getByLabelText('Type your username (ada)'), 'ad');
    expect(submit).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Type your username (ada)'), 'a');
    await userEvent.click(submit);

    expect(deleteAccount).toHaveBeenCalledWith({
      username: 'ada',
      password: 'Str0ng-Passw0rd!',
      mfaCode: undefined,
    });
    expect(await screen.findByText('login page')).toBeInTheDocument();
    expect(useAuthStore.getState().user).toBeNull();
  });

  it("shows the server's reason when an organization would be left without an owner", async () => {
    const error = new AxiosError('409');
    error.response = {
      status: 409,
      statusText: 'Conflict',
      headers: {},
      config: { headers: new AxiosHeaders() },
      data: { message: 'You are the only owner of Acme.' },
    };
    deleteAccount.mockRejectedValue(error);
    renderIt();
    await userEvent.click(screen.getByRole('button', { name: 'Delete my account' }));
    await userEvent.type(screen.getByLabelText('Type your username (ada)'), 'ada');
    await userEvent.type(screen.getByLabelText('Password'), 'Str0ng-Passw0rd!');
    await userEvent.click(screen.getByRole('button', { name: 'Delete account permanently' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('You are the only owner of Acme.');
    expect(useAuthStore.getState().user).not.toBeNull();
  });

  it('asks for an authentication code when two-factor is on', async () => {
    mfaStatus.mockResolvedValue(env({ enabled: true, remainingBackupCodes: 8 }));
    renderIt();
    await userEvent.click(screen.getByRole('button', { name: 'Delete my account' }));
    expect(await screen.findByLabelText('Authentication code')).toBeInTheDocument();
  });
});
