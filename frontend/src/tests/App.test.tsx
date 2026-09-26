import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it } from 'vitest';

import App from '../App';

function renderAt(route: string) {
  // A fresh client per test, with retries off: a retrying query turns an
  // expected failure into a multi-second wait.
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[route]}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('App routing', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('sends an anonymous visitor to the sign-in page', async () => {
    renderAt('/');

    // The dashboard is behind RequireAuth, so an unauthenticated visit must
    // land on login rather than rendering a half-populated shell.
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
