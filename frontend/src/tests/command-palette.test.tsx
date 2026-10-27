import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { CommandPalette } from '@/components/layout/CommandPalette';

const listOrganizations = vi.fn();
const listProjects = vi.fn();

vi.mock('@/api/project.api', () => ({
  projectApi: {
    listOrganizations: (...a: unknown[]) => listOrganizations(...a),
    listProjects: (...a: unknown[]) => listProjects(...a),
  },
}));

const env = <T,>(data: T) => ({ data: { data } });

function Where() {
  return <p data-testid="where">{useLocation().pathname}</p>;
}

function renderIt(onClose = vi.fn()) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/']}>
        <CommandPalette open onClose={onClose} />
        <Routes>
          <Route path="*" element={<Where />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return onClose;
}

describe('CommandPalette', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listOrganizations.mockResolvedValue(
      env([{ id: 'o1', name: 'Platform', slug: 'platform', description: null, role: 'OWNER' }]),
    );
    listProjects.mockResolvedValue(
      env({
        content: [
          { id: 'p1', organizationId: 'o1', name: 'Calculator', projectKey: 'CALC' },
          { id: 'p2', organizationId: 'o1', name: 'Website', projectKey: 'WEB' },
        ],
      }),
    );
  });

  it("finds the viewer's projects and opens one from the keyboard", async () => {
    renderIt();
    const box = screen.getByRole('combobox', { name: /Search pages/ });
    expect(box).toHaveFocus();

    await userEvent.type(box, 'calc');
    expect(await screen.findByRole('option', { name: /Calculator/ })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    expect(screen.queryByRole('option', { name: /Website/ })).toBeNull();

    await userEvent.keyboard('{Enter}');
    expect(screen.getByTestId('where')).toHaveTextContent('/organizations/o1/projects/p1');
  });

  it('puts the best match first: a name starting with the query beats one containing it', async () => {
    renderIt();
    await screen.findByRole('option', { name: /Calculator/ });
    await userEvent.type(screen.getByRole('combobox', { name: /Search pages/ }), 'ca');

    const options = screen.getAllByRole('option');
    expect(options[0]).toHaveTextContent('Calculator');
    expect(options[0]).toHaveAttribute('aria-selected', 'true');
    expect(options.map((o) => o.textContent)).toEqual(
      expect.arrayContaining([expect.stringContaining('Notifications')]),
    );
  });

  it('moves with the arrow keys and points the input at the active option', async () => {
    renderIt();
    const box = screen.getByRole('combobox', { name: /Search pages/ });
    await screen.findByRole('option', { name: /Calculator/ });

    await userEvent.keyboard('{ArrowDown}');
    const second = screen.getAllByRole('option')[1];
    expect(second).toHaveAttribute('aria-selected', 'true');
    expect(box).toHaveAttribute('aria-activedescendant', second.id);
  });

  it('says when nothing matches, and Escape closes', async () => {
    const onClose = renderIt();
    await userEvent.type(screen.getByRole('combobox', { name: /Search pages/ }), 'zzz');
    expect(screen.getByText(/Nothing matches/)).toBeInTheDocument();

    await userEvent.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalled();
  });
});
