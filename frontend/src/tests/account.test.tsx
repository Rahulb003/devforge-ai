import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AxiosError, AxiosHeaders } from 'axios';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { UserProfile } from '@/api/auth.api';
import { PasswordSection } from '@/components/settings/PasswordSection';
import { ProfileSection } from '@/components/settings/ProfileSection';
import { useAuthStore } from '@/stores/authStore';

const updateProfile = vi.fn();
const changePassword = vi.fn();

vi.mock('@/api/auth.api', () => ({
  authApi: {
    updateProfile: (...a: unknown[]) => updateProfile(...a),
    changePassword: (...a: unknown[]) => changePassword(...a),
  },
}));

const env = <T,>(data: T) => ({ data: { data } });

function renderWith(node: ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}>{node}</QueryClientProvider>);
}

function refused(message: string) {
  const error = new AxiosError('400');
  error.response = {
    status: 400,
    statusText: 'Bad Request',
    headers: {},
    config: { headers: new AxiosHeaders() },
    data: { message },
  };
  return error;
}

describe('ProfileSection', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAuthStore.setState({
      user: {
        firstName: 'Ada',
        lastName: 'Lovelace',
        username: 'ada',
        email: 'ada@example.com',
        emailVerified: true,
        timezone: null,
        language: null,
      } as unknown as UserProfile,
    });
  });

  it('saves the edited fields and updates the signed-in user', async () => {
    updateProfile.mockResolvedValue(
      env({
        firstName: 'Augusta',
        lastName: 'Lovelace',
        timezone: 'Europe/London',
        language: null,
      }),
    );
    renderWith(<ProfileSection />);

    const first = screen.getByLabelText('First name');
    await userEvent.clear(first);
    await userEvent.type(first, 'Augusta');
    await userEvent.type(screen.getByLabelText('Time zone'), 'Europe/London');
    await userEvent.click(screen.getByRole('button', { name: 'Save profile' }));

    // A blank language is left alone rather than sent as a change.
    expect(updateProfile).toHaveBeenCalledWith({
      firstName: 'Augusta',
      lastName: 'Lovelace',
      timezone: 'Europe/London',
    });
    expect(await screen.findByRole('status')).toHaveTextContent('Profile saved');
    expect(useAuthStore.getState().user?.firstName).toBe('Augusta');
  });

  it("shows the server's reason when a value is refused", async () => {
    updateProfile.mockRejectedValue(
      refused('Unknown time zone: use a region such as Europe/Berlin'),
    );
    renderWith(<ProfileSection />);
    await userEvent.type(screen.getByLabelText('Time zone'), 'Mars/Olympus');
    await userEvent.click(screen.getByRole('button', { name: 'Save profile' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/Unknown time zone/);
  });
});

describe('PasswordSection', () => {
  beforeEach(() => vi.clearAllMocks());

  async function fill(current: string, next: string, confirm: string) {
    await userEvent.type(screen.getByLabelText('Current password'), current);
    await userEvent.type(screen.getByLabelText('New password'), next);
    await userEvent.type(screen.getByLabelText('Confirm new password'), confirm);
  }

  it('will not submit when the confirmation differs', async () => {
    renderWith(<PasswordSection />);
    await fill('Old-Passw0rd!!', 'New-Passw0rd!!', 'New-Passw0rd!?');
    expect(screen.getByText('Does not match the new password')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Change password' })).toBeDisabled();
    expect(changePassword).not.toHaveBeenCalled();
  });

  it('says how many other devices were signed out', async () => {
    changePassword.mockResolvedValue(env({ otherSessionsSignedOut: 2 }));
    renderWith(<PasswordSection />);
    await fill('Old-Passw0rd!!', 'New-Passw0rd!!', 'New-Passw0rd!!');
    await userEvent.click(screen.getByRole('button', { name: 'Change password' }));

    expect(changePassword).toHaveBeenCalledWith('Old-Passw0rd!!', 'New-Passw0rd!!');
    expect(await screen.findByRole('status')).toHaveTextContent('2 other devices were signed out');
    // The fields are cleared, so the password does not linger in the form.
    expect(screen.getByLabelText('New password')).toHaveValue('');
  });

  it('shows a wrong current password as the server words it', async () => {
    changePassword.mockRejectedValue(refused('Current password is incorrect'));
    renderWith(<PasswordSection />);
    await fill('Wrong-Passw0rd!', 'New-Passw0rd!!', 'New-Passw0rd!!');
    await userEvent.click(screen.getByRole('button', { name: 'Change password' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Current password is incorrect');
  });
});
