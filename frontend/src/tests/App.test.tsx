import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';

import App from '../App';

function renderAt(route: string) {
  return render(
    <MemoryRouter initialEntries={[route]}>
      <App />
    </MemoryRouter>,
  );
}

describe('App shell', () => {
  it('renders the branded sidebar link on the default route', () => {
    renderAt('/');

    // Queried by role rather than raw text: "DevForge AI" also appears in the
    // dashboard heading, so a bare getByText matches multiple nodes.
    expect(screen.getByRole('link', { name: /DevForge AI/i })).toBeInTheDocument();
  });

  it('renders the dashboard at the index route', () => {
    renderAt('/');

    expect(screen.getByRole('heading', { name: /Welcome to DevForge AI/i })).toBeInTheDocument();
  });

  it('redirects an unknown route to the not-found page', () => {
    renderAt('/this-route-does-not-exist');

    // The layout header also echoes the "404" path segment, so assert on the
    // page's own heading instead of the bare string.
    expect(screen.getByRole('heading', { name: /Page not found/i })).toBeInTheDocument();
  });
});
