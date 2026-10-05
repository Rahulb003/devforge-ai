import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AxiosError, AxiosHeaders } from 'axios';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { Finding, Review } from '@/api/review.api';
import { ReviewPage } from '@/pages/ReviewPage';

const latest = vi.fn();
const run = vi.fn();
const findings = vi.fn();
const dismiss = vi.fn();

vi.mock('@/api/review.api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/review.api')>();
  return {
    ...actual,
    reviewApi: {
      latest: (...a: unknown[]) => latest(...a),
      run: (...a: unknown[]) => run(...a),
      findings: (...a: unknown[]) => findings(...a),
      get: vi.fn(),
      list: vi.fn(),
      dismiss: (...a: unknown[]) => dismiss(...a),
    },
  };
});

vi.mock('@/api/git.api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/git.api')>();
  return {
    ...actual,
    gitApi: {
      ...actual.gitApi,
      get: vi.fn().mockResolvedValue({ data: { data: { id: 'repo-1', name: 'payments-api' } } }),
    },
  };
});

const ORG = 'org-1';
const PROJ = 'proj-1';
const REPO = 'repo-1';

function envelope<T>(data: T) {
  return { data: { data } };
}

function review(overrides: Partial<Review> = {}): Review {
  return {
    id: 'rev-1',
    repositoryId: REPO,
    ref: 'main',
    baseRef: null,
    status: 'COMPLETED',
    gate: 'PASS',
    filesAnalysed: 12,
    blockerCount: 0,
    highCount: 0,
    mediumCount: 0,
    lowCount: 0,
    failureReason: null,
    requestedBy: 'u1',
    createdAt: new Date().toISOString(),
    completedAt: new Date().toISOString(),
    ...overrides,
  };
}

function finding(overrides: Partial<Finding> = {}): Finding {
  return {
    id: 'f-1',
    ruleId: 'SECRET_AWS_ACCESS_KEY_ID',
    severity: 'BLOCKER',
    category: 'SECRET',
    filePath: 'src/Config.java',
    lineNumber: 2,
    message: 'This line appears to contain an AWS access key id.',
    snippet: 'String KEY = "AKIA[REDACTED 20 chars]";',
    dismissed: false,
    dismissedAt: null,
    dismissedBy: null,
    dismissReason: null,
    ...overrides,
  };
}

/** A 404 from `latest`, which means "never reviewed" rather than a failure. */
function notFound() {
  const error = new AxiosError('Request failed with status code 404');
  error.response = {
    status: 404,
    statusText: 'Not Found',
    headers: {},
    config: { headers: new AxiosHeaders() },
    data: { message: 'This repository has not been reviewed yet' },
  };
  return error;
}

function renderPage(ui: ReactNode = <ReviewPage />) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  return render(
    <MemoryRouter
      initialEntries={[`/organizations/${ORG}/projects/${PROJ}/repositories/${REPO}/review`]}
    >
      <QueryClientProvider client={queryClient}>
        <Routes>
          <Route
            path="/organizations/:organizationId/projects/:projectId/repositories/:repositoryId/review"
            element={ui}
          />
        </Routes>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe('ReviewPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    findings.mockResolvedValue(envelope([]));
  });

  it('invites a first review when the repository has never been reviewed', async () => {
    // A 404 here is a normal state, not an error, and must not render as one.
    latest.mockRejectedValue(notFound());

    renderPage();

    expect(await screen.findByText('This repository has not been reviewed')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /run a review/i })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('shows a passing gate with the file count', async () => {
    latest.mockResolvedValue(envelope(review()));

    renderPage();

    expect(await screen.findByText('Quality gate passed')).toBeInTheDocument();
    expect(screen.getByText(/12 files analysed/)).toBeInTheDocument();
  });

  it('shows a failing gate with the severity counts', async () => {
    latest.mockResolvedValue(
      envelope(review({ gate: 'FAIL', blockerCount: 1, highCount: 2, mediumCount: 3 })),
    );
    findings.mockResolvedValue(envelope([finding()]));

    renderPage();

    expect(await screen.findByText('Quality gate failed')).toBeInTheDocument();
    expect(screen.getByText('1 blocker')).toBeInTheDocument();
    expect(screen.getByText('2 high')).toBeInTheDocument();
    expect(screen.getByText('3 medium')).toBeInTheDocument();
  });

  it('never displays the credential, only the redacted snippet', async () => {
    latest.mockResolvedValue(envelope(review({ gate: 'FAIL', blockerCount: 1 })));
    findings.mockResolvedValue(envelope([finding()]));

    renderPage();

    expect(await screen.findByText(/src\/Config\.java/)).toBeInTheDocument();
    // The server redacts before storing; the UI must not undo that by rendering something else.
    expect(screen.getByText(/REDACTED/)).toBeInTheDocument();
    expect(screen.queryByText(/AKIAIOSFODNN7EXAMPLX/)).not.toBeInTheDocument();
  });

  it('says a failed review could not read the code, rather than implying a pass', async () => {
    latest.mockResolvedValue(
      envelope(
        review({
          status: 'FAILED',
          gate: null,
          failureReason: 'Cannot read the repository right now. Please try again.',
          filesAnalysed: 0,
        }),
      ),
    );

    renderPage();

    // "No findings" on a review that analysed nothing is the most dangerous thing this page could
    // show, because it reads as a clean repository.
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/could not read the code/i);
    expect(alert).toHaveTextContent(/Cannot read the repository/i);
    expect(screen.queryByText('No findings')).not.toBeInTheDocument();
  });

  it('runs a review on request', async () => {
    latest.mockRejectedValue(notFound());
    run.mockResolvedValue(envelope(review()));

    renderPage();
    await screen.findByText('This repository has not been reviewed');

    await userEvent.click(screen.getByRole('button', { name: /run a review/i }));

    await waitFor(() => expect(run).toHaveBeenCalledWith(ORG, PROJ, REPO));
  });

  it('surfaces an unreadable repository when running', async () => {
    latest.mockRejectedValue(notFound());
    const error = new AxiosError('Request failed with status code 503');
    error.response = {
      status: 503,
      statusText: 'Service Unavailable',
      headers: {},
      config: { headers: new AxiosHeaders() },
      data: { message: 'Cannot read the repository right now. Please try again.' },
    };
    run.mockRejectedValue(error);

    renderPage();
    await screen.findByText('This repository has not been reviewed');
    await userEvent.click(screen.getByRole('button', { name: /run a review/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/Cannot read the repository/i);
  });

  it('requires a reason before a finding can be dismissed', async () => {
    latest.mockResolvedValue(envelope(review({ gate: 'FAIL', blockerCount: 1 })));
    findings.mockResolvedValue(envelope([finding()]));

    renderPage();
    await screen.findByText(/src\/Config\.java/);

    await userEvent.click(screen.getByRole('button', { name: /dismiss…/i }));

    // The server rejects an empty reason; the UI should not let the user discover that the hard way.
    expect(screen.getByRole('button', { name: 'Dismiss' })).toBeDisabled();

    await userEvent.type(screen.getByLabelText(/why is this acceptable/i), 'Key already rotated');
    expect(screen.getByRole('button', { name: 'Dismiss' })).toBeEnabled();

    dismiss.mockResolvedValue(envelope(finding({ dismissed: true })));
    await userEvent.click(screen.getByRole('button', { name: 'Dismiss' }));

    await waitFor(() =>
      expect(dismiss).toHaveBeenCalledWith(ORG, PROJ, REPO, 'rev-1', 'f-1', 'Key already rotated'),
    );
  });

  it('marks a dismissed finding and shows the reason', async () => {
    latest.mockResolvedValue(envelope(review({ gate: 'FAIL', blockerCount: 1 })));
    findings.mockResolvedValue(
      envelope([finding({ dismissed: true, dismissReason: 'Key already rotated' })]),
    );

    renderPage();

    expect(await screen.findByText('Dismissed')).toBeInTheDocument();
    expect(screen.getByText(/Key already rotated/)).toBeInTheDocument();
    // The gate still reports what the analysis found; a dismissal does not rewrite history.
    expect(screen.getByText('Quality gate failed')).toBeInTheDocument();
  });

  it('offers a retry when the findings cannot be loaded', async () => {
    latest.mockResolvedValue(envelope(review({ gate: 'FAIL', blockerCount: 1 })));
    findings.mockRejectedValue(new Error('boom'));

    renderPage();

    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /try again|retry/i })).toBeInTheDocument();
  });
});
