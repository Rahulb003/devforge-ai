import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AxiosError, AxiosHeaders } from 'axios';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { PipelinesPage } from '@/pages/PipelinesPage';

const trigger = vi.fn();
const list = vi.fn();
const get = vi.fn();

vi.mock('@/api/pipeline.api', async (original) => ({
  ...(await original<typeof import('@/api/pipeline.api')>()),
  pipelineApi: {
    trigger: (...a: unknown[]) => trigger(...a),
    list: (...a: unknown[]) => list(...a),
    get: (...a: unknown[]) => get(...a),
  },
}));

const env = <T,>(data: T) => ({ data: { data } });

const finishedRun = {
  id: 'r1',
  repositoryId: 'repo',
  ref: 'main',
  status: 'FAILED',
  message: "Step 'test' did not succeed",
  omittedFiles: ['logo.png'],
  triggeredBy: 'u1',
  createdAt: '2026-10-27T00:00:00Z',
  startedAt: '2026-10-27T00:00:01Z',
  finishedAt: '2026-10-27T00:00:05Z',
  steps: [
    {
      position: 1,
      name: 'compile',
      runtime: 'PYTHON',
      script: 'python -m py_compile calc.py',
      timeoutSeconds: 120,
      status: 'SUCCEEDED',
      exitCode: 0,
      stdout: '',
      stderr: '',
      truncated: false,
      durationMs: 700,
    },
    {
      position: 2,
      name: 'test',
      runtime: 'PYTHON',
      script: 'python -m unittest',
      timeoutSeconds: 120,
      status: 'FAILED',
      exitCode: 1,
      stdout: '',
      stderr: '<b>AssertionError</b>: 5 != -1',
      truncated: false,
      durationMs: 800,
    },
  ],
};

function renderIt() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/organizations/o/projects/p/repositories/repo/pipelines']}>
        <Routes>
          <Route
            path="/organizations/:organizationId/projects/:projectId/repositories/:repositoryId/pipelines"
            element={<PipelinesPage />}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('PipelinesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    list.mockResolvedValue(env([]));
  });

  it('runs the pipeline and shows each step, with output as text', async () => {
    trigger.mockResolvedValue(env({ ...finishedRun, status: 'QUEUED', steps: [] }));
    get.mockResolvedValue(env(finishedRun));
    renderIt();
    expect(await screen.findByText('No pipeline runs yet')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Run pipeline' }));

    expect(trigger).toHaveBeenCalledWith('o', 'p', 'repo', '');
    const card = await screen.findByTestId('pipeline-run');
    expect(await within(card).findByText(/did not succeed/)).toBeInTheDocument();
    expect(within(card).getByText('compile')).toBeInTheDocument();
    expect(within(card).getByText('<b>AssertionError</b>: 5 != -1')).toBeInTheDocument();
    expect(card.querySelector('b')).toBeNull();
    expect(within(card).getByText(/logo\.png/)).toBeInTheDocument();
  });

  it('opens on the failing station, and another station shows its own output', async () => {
    trigger.mockResolvedValue(env({ ...finishedRun, status: 'QUEUED', steps: [] }));
    get.mockResolvedValue(env(finishedRun));
    renderIt();
    await userEvent.click(await screen.findByRole('button', { name: 'Run pipeline' }));

    const failing = await screen.findByRole('button', { name: /^test/ });
    expect(failing).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('region', { name: 'Output of step 2, test' })).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /^compile/ }));
    const output = screen.getByRole('region', { name: 'Output of step 1, compile' });
    expect(output).toHaveTextContent('python -m py_compile calc.py');
    expect(output).not.toHaveTextContent('AssertionError');
  });

  it('refreshes the run list once the watched run finishes', async () => {
    // On load, right after the trigger (still running), and - the fix - once it has finished.
    list
      .mockResolvedValueOnce(env([]))
      .mockResolvedValueOnce(env([{ ...finishedRun, status: 'RUNNING', finishedAt: null }]))
      .mockResolvedValue(env([finishedRun]));
    trigger.mockResolvedValue(env({ ...finishedRun, status: 'QUEUED', steps: [] }));
    get.mockResolvedValue(env(finishedRun));
    renderIt();
    await userEvent.click(await screen.findByRole('button', { name: 'Run pipeline' }));

    expect(await screen.findByRole('button', { name: /^Failed · main/ })).toBeInTheDocument();
  });

  it('explains a repository without a pipeline file', async () => {
    const error = new AxiosError('400');
    error.response = {
      status: 400,
      statusText: 'Bad Request',
      headers: {},
      config: { headers: new AxiosHeaders() },
      data: {
        message: 'No pipeline is defined at main: add .devforge/pipeline.yml to the repository',
      },
    };
    trigger.mockRejectedValue(error);
    renderIt();

    await userEvent.click(screen.getByRole('button', { name: 'Run pipeline' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('add .devforge/pipeline.yml');
  });
});
