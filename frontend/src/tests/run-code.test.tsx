import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AxiosError, AxiosHeaders } from 'axios';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { RunCodePage } from '@/pages/RunCodePage';

const run = vi.fn();
const list = vi.fn();
const get = vi.fn();

vi.mock('@/api/execution.api', async (original) => ({
  ...(await original<typeof import('@/api/execution.api')>()),
  executionApi: {
    run: (...a: unknown[]) => run(...a),
    list: (...a: unknown[]) => list(...a),
    get: (...a: unknown[]) => get(...a),
  },
}));

const env = <T,>(data: T) => ({ data: { data } });

function renderIt() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/organizations/o1/projects/p1/run']}>
        <Routes>
          <Route
            path="/organizations/:organizationId/projects/:projectId/run"
            element={<RunCodePage />}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('RunCodePage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    list.mockResolvedValue(env([]));
  });

  it('runs the code and shows the output as text, not markup', async () => {
    run.mockResolvedValue(
      env({
        id: 'e1',
        userId: 'u1',
        language: 'PYTHON',
        code: 'print(1)',
        status: 'COMPLETED',
        exitCode: 0,
        stdout: '<img src=x onerror=alert(1)>\n',
        stderr: '',
        truncated: false,
        durationMs: 640,
        timeoutSeconds: 10,
        memoryMb: 256,
        createdAt: '2026-10-27T00:00:00Z',
        finishedAt: '2026-10-27T00:00:01Z',
      }),
    );
    renderIt();
    expect(await screen.findByText('No runs yet')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Run' }));

    expect(run).toHaveBeenCalledWith('o1', 'p1', 'PYTHON', 'print("Hello from the sandbox")');
    const result = await screen.findByTestId('run-result');
    expect(within(result).getByText(/Completed/)).toBeInTheDocument();
    expect(within(result).getByText('<img src=x onerror=alert(1)>')).toBeInTheDocument();
    expect(result.querySelector('img')).toBeNull();
  });

  it('shows why a run was refused', async () => {
    const error = new AxiosError('503');
    error.response = {
      status: 503,
      statusText: 'Service Unavailable',
      headers: {},
      config: { headers: new AxiosHeaders() },
      data: { message: 'Code execution is not enabled on this deployment' },
    };
    run.mockRejectedValue(error);
    renderIt();

    await userEvent.click(screen.getByRole('button', { name: 'Run' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Code execution is not enabled on this deployment',
    );
  });
});
