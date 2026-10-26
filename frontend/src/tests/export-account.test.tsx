import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AxiosError, AxiosHeaders } from 'axios';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ExportAccountSection } from '@/components/settings/ExportAccountSection';

const exportAccount = vi.fn();

vi.mock('@/api/auth.api', () => ({
  authApi: { exportAccount: (...a: unknown[]) => exportAccount(...a) },
}));

function renderIt() {
  const qc = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <ExportAccountSection />
    </QueryClientProvider>,
  );
}

describe('ExportAccountSection', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    URL.createObjectURL = vi.fn(() => 'blob:export');
    URL.revokeObjectURL = vi.fn();
  });

  it('saves the response as a file', async () => {
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    const blob = new Blob(['{}'], { type: 'application/json' });
    exportAccount.mockResolvedValue({ data: blob });
    renderIt();

    await userEvent.click(screen.getByRole('button', { name: 'Download account data' }));

    expect(URL.createObjectURL).toHaveBeenCalledWith(blob);
    expect(click).toHaveBeenCalled();
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:export');
  });

  it("shows the server's message, which arrives as a blob", async () => {
    const error = new AxiosError('503');
    error.response = {
      status: 503,
      statusText: 'Service Unavailable',
      headers: {},
      config: { headers: new AxiosHeaders() },
      data: new Blob([JSON.stringify({ message: 'Export is temporarily unavailable.' })]),
    };
    exportAccount.mockRejectedValue(error);
    renderIt();

    await userEvent.click(screen.getByRole('button', { name: 'Download account data' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Export is temporarily unavailable.',
    );
  });
});
