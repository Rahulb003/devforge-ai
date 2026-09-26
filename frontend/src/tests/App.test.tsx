import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import App from '../App';

// The dashboard loads organizations on mount. Stubbed so these tests exercise
// routing and composition rather than the network.
vi.mock('@/api/project.api', () => ({
  projectApi: {
    listOrganizations: vi.fn().mockResolvedValue({ data: { data: [] } }),
  },
}));

/**
 * Renders exactly what main.tsx renders, minus the browser router.
 *
 * Deliberately supplies no QueryClientProvider. An earlier version of this file
 * wrapped App in its own provider, which meant the suite passed while the real
 * entry point had none at all — every screen calling useQuery threw on mount
 * and only the error boundary was visible. App owns its providers now, and
 * these tests prove it by not supplying them.
 */
function renderAt(route: string) {
  return render(
    <MemoryRouter initialEntries={[route]}>
      <App />
    </MemoryRouter>,
  );
}

describe('App routing', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.clearAllMocks();
  });

  it('sends an anonymous visitor to the sign-in page', async () => {
    renderAt('/');

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /sign in/i })).toBeInTheDocument();
    });
  });

  it('renders the sign-up form', async () => {
    renderAt('/signup');

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /create your account/i })).toBeInTheDocument();
    });
    expect(screen.getByLabelText(/^username$/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/^password$/i)).toBeInTheDocument();
  });

  it('renders the forgot-password form', async () => {
    renderAt('/forgot-password');

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /reset your password/i })).toBeInTheDocument();
    });
  });

  it('tells the user when a verification link carries no token', async () => {
    renderAt('/verify-email');

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /no verification token/i })).toBeInTheDocument();
    });
  });

  it('tells the user when a reset link carries no token', async () => {
    renderAt('/reset-password');

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /invalid reset link/i })).toBeInTheDocument();
    });
  });

  it('redirects an unknown route to the not-found page', async () => {
    renderAt('/this-route-does-not-exist');

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /page not found/i })).toBeInTheDocument();
    });
  });
});

describe('Signed-in shell', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.clearAllMocks();

    // What authStore.initializeAuth reads back to restore a session.
    localStorage.setItem('access_token', 'a-token');
    localStorage.setItem(
      'user',
      JSON.stringify({
        id: '00000000-0000-0000-0000-000000000001',
        firstName: 'Ada',
        lastName: 'Lovelace',
        username: 'ada',
        email: 'ada@example.com',
        roles: ['DEVELOPER'],
        status: 'ACTIVE',
        emailVerified: true,
        darkMode: true,
        createdAt: '2026-01-01T00:00:00Z',
      }),
    );
  });

  it('renders the dashboard after sign-in instead of the error boundary', async () => {
    // This is the path that was broken: the dashboard is the first screen a user
    // reaches after signing in, and the first that calls useQuery.
    renderAt('/');

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /welcome back/i })).toBeInTheDocument();
    });
    expect(screen.queryByText(/something went wrong/i)).not.toBeInTheDocument();
  });

  it('renders the organizations screen', async () => {
    renderAt('/organizations');

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /^organizations$/i })).toBeInTheDocument();
    });
    expect(screen.queryByText(/something went wrong/i)).not.toBeInTheDocument();
  });
});
