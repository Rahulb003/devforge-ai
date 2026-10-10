import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { AiAsk } from '@/components/ai/AiAsk';

const status = vi.fn();
const ask = vi.fn();

vi.mock('@/api/ai.api', () => ({
  aiApi: {
    status: (...a: unknown[]) => status(...a),
    ask: (...a: unknown[]) => ask(...a),
  },
}));

const env = <T,>(data: T) => ({ data: { data } });

function renderIt() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <AiAsk organizationId="o" projectId="p" repositoryId="r" gitRef="main" />
    </QueryClientProvider>,
  );
}

describe('AiAsk', () => {
  beforeEach(() => vi.clearAllMocks());

  it('is not shown where AI is not configured', async () => {
    status.mockResolvedValue(env({ configured: false, model: '' }));
    renderIt();
    await waitFor(() => expect(status).toHaveBeenCalled());
    expect(screen.queryByLabelText('Ask about this repository')).not.toBeInTheDocument();
  });

  it('answers as text and names the files it used', async () => {
    status.mockResolvedValue(env({ configured: true, model: 'claude-opus-5-5' }));
    ask.mockResolvedValue(
      env({
        question: 'Where are tokens validated?',
        answer: 'In src/auth/token.ts.',
        model: 'claude-opus-5-5',
        sources: ['src/auth/token.ts'],
        filesSearched: 40,
        truncated: false,
      }),
    );
    renderIt();
    await userEvent.type(
      await screen.findByLabelText('Ask about this repository'),
      'Where are tokens validated?',
    );
    await userEvent.click(screen.getByRole('button', { name: 'Ask' }));

    expect(ask).toHaveBeenCalledWith('o', 'p', 'r', 'Where are tokens validated?', 'main');
    const answer = await screen.findByRole('region', { name: 'AI answer' });
    expect(answer).toHaveTextContent('In src/auth/token.ts.');
    expect(answer).toHaveTextContent('using src/auth/token.ts');
    expect(answer).toHaveTextContent('40 files searched');
  });
});
