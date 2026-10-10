import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AxiosError, AxiosHeaders } from 'axios';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { AiExplain } from '@/components/ai/AiExplain';

const status = vi.fn();
const explain = vi.fn();

vi.mock('@/api/ai.api', () => ({
  aiApi: {
    status: (...a: unknown[]) => status(...a),
    explain: (...a: unknown[]) => explain(...a),
  },
}));

const env = <T,>(data: T) => ({ data: { data } });

function renderIt() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <AiExplain organizationId="o" projectId="p" repositoryId="r" path="src/a.ts" gitRef="main" />
    </QueryClientProvider>,
  );
}

describe('AiExplain', () => {
  beforeEach(() => vi.clearAllMocks());

  it('is disabled, and says why, where no key is configured', async () => {
    status.mockResolvedValue(env({ configured: false, model: '' }));
    renderIt();
    expect(await screen.findByText(/not configured on this deployment/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Explain with AI' })).toBeDisabled();
  });

  it('shows the explanation as text, never as markup', async () => {
    status.mockResolvedValue(env({ configured: true, model: 'claude-opus-5-5' }));
    explain.mockResolvedValue(
      env({
        path: 'src/a.ts',
        ref: 'main',
        explanation: 'It adds. <img src=x onerror=alert(1)>',
        model: 'claude-opus-5-5',
        truncated: false,
      }),
    );
    renderIt();
    await userEvent.click(await screen.findByRole('button', { name: 'Explain with AI' }));

    expect(explain).toHaveBeenCalledWith('o', 'p', 'r', 'src/a.ts', 'main');
    const section = await screen.findByRole('region', { name: 'AI explanation' });
    expect(section).toHaveTextContent('It adds. <img src=x onerror=alert(1)>');
    expect(section.querySelector('img')).toBeNull();
    expect(section).toHaveTextContent('Written by claude-opus-5-5');
  });

  it("shows the server's reason when it cannot answer", async () => {
    status.mockResolvedValue(env({ configured: true, model: 'claude-opus-5-5' }));
    const busy = new AxiosError('503');
    busy.response = {
      status: 503,
      statusText: 'Service Unavailable',
      headers: {},
      config: { headers: new AxiosHeaders() },
      data: { message: 'The AI provider is busy; try again in a minute' },
    };
    explain.mockRejectedValue(busy);
    renderIt();
    await userEvent.click(await screen.findByRole('button', { name: 'Explain with AI' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('busy');
  });
});
