import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { AiSuggest } from '@/components/ai/AiSuggest';

const status = vi.fn();
const suggest = vi.fn();

vi.mock('@/api/ai.api', () => ({
  aiApi: {
    status: (...a: unknown[]) => status(...a),
    suggest: (...a: unknown[]) => suggest(...a),
  },
}));

const env = <T,>(data: T) => ({ data: { data } });

describe('AiSuggest', () => {
  it('shows the proposal and hands it to the editor only when asked', async () => {
    status.mockResolvedValue(env({ configured: true, model: 'claude-opus-5-5' }));
    suggest.mockResolvedValue(
      env({
        path: 'src/add.ts',
        ref: 'main',
        request: 'Add types',
        proposed: 'export const add = (a: number, b: number) => a + b;\n',
        model: 'claude-opus-5-5',
      }),
    );
    const onUse = vi.fn();
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={qc}>
        <AiSuggest
          organizationId="o"
          projectId="p"
          repositoryId="r"
          path="src/add.ts"
          gitRef="main"
          onUse={onUse}
        />
      </QueryClientProvider>,
    );

    await userEvent.type(
      await screen.findByLabelText('Describe a change to this file'),
      'Add types',
    );
    await userEvent.click(screen.getByRole('button', { name: 'Suggest change' }));

    const region = await screen.findByRole('region', { name: 'Suggested change' });
    expect(region).toHaveTextContent('Nothing has changed yet');
    expect(onUse).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: 'Open in editor' }));
    expect(onUse).toHaveBeenCalledWith('export const add = (a: number, b: number) => a + b;\n');
  });
});
