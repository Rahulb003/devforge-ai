import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AxiosError, AxiosHeaders } from 'axios';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { DocSet, GeneratedDocument } from '@/api/docs.api';
import { DocsPage } from '@/pages/DocsPage';

const latest = vi.fn();
const generate = vi.fn();
const documents = vi.fn();
const document_ = vi.fn();

vi.mock('@/api/docs.api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/docs.api')>();
  return {
    ...actual,
    docsApi: {
      latest: (...a: unknown[]) => latest(...a),
      generate: (...a: unknown[]) => generate(...a),
      list: vi.fn(),
      documents: (...a: unknown[]) => documents(...a),
      document: (...a: unknown[]) => document_(...a),
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

const envelope = <T,>(data: T) => ({ data: { data } });

function docSet(overrides: Partial<DocSet> = {}): DocSet {
  return {
    id: 'set-1',
    repositoryId: REPO,
    ref: 'main',
    status: 'COMPLETED',
    filesScanned: 12,
    failureReason: null,
    generatedBy: 'u1',
    createdAt: new Date().toISOString(),
    completedAt: new Date().toISOString(),
    ...overrides,
  };
}

function doc(kind: GeneratedDocument['kind'], content: string | null): GeneratedDocument {
  return {
    id: `doc-${kind}`,
    kind,
    title: kind,
    content,
    createdAt: new Date().toISOString(),
  };
}

function notFound() {
  const error = new AxiosError('404');
  error.response = {
    status: 404,
    statusText: 'Not Found',
    headers: {},
    config: { headers: new AxiosHeaders() },
    data: { message: 'No documentation has been generated for this repository' },
  };
  return error;
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  return render(
    <MemoryRouter
      initialEntries={[`/organizations/${ORG}/projects/${PROJ}/repositories/${REPO}/docs`]}
    >
      <QueryClientProvider client={queryClient}>
        <Routes>
          <Route
            path="/organizations/:organizationId/projects/:projectId/repositories/:repositoryId/docs"
            element={<DocsPage />}
          />
        </Routes>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe('DocsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    documents.mockResolvedValue(envelope([]));
  });

  it('invites a first generation when there is none', async () => {
    // A 404 is a normal state, not an error, and must not render as one.
    latest.mockRejectedValue(notFound());

    renderPage();

    expect(await screen.findByText('No documentation yet')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('generates on request', async () => {
    latest.mockRejectedValue(notFound());
    generate.mockResolvedValue(envelope(docSet()));

    renderPage();
    await screen.findByText('No documentation yet');
    await userEvent.click(screen.getByRole('button', { name: 'Generate' }));

    await waitFor(() => expect(generate).toHaveBeenCalledWith(ORG, PROJ, REPO));
  });

  it('lists a tab per generated document and opens one', async () => {
    latest.mockResolvedValue(envelope(docSet()));
    documents.mockResolvedValue(envelope([doc('OVERVIEW', null), doc('API_SURFACE', null)]));
    document_.mockResolvedValue(
      envelope(doc('OVERVIEW', '# Repository overview\n\nJava, 12 files')),
    );

    renderPage();

    expect(await screen.findByRole('tab', { name: 'Overview' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'API surface' })).toBeInTheDocument();
    // Doc coverage produced nothing for this repository, so there is no tab for it.
    expect(screen.queryByRole('tab', { name: 'Doc coverage' })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('tab', { name: 'Overview' }));

    expect(await screen.findByText(/Java, 12 files/)).toBeInTheDocument();
  });

  it('says a failed set could not be generated, rather than showing it as empty', async () => {
    latest.mockResolvedValue(
      envelope(
        docSet({
          status: 'FAILED',
          filesScanned: 0,
          failureReason: 'Cannot read the repository right now. Please try again.',
        }),
      ),
    );

    renderPage();

    // "Nothing to document" on a set that read nothing is the dangerous reading: it sounds like a
    // fact about the repository rather than a failure.
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/could not be generated/i);
    expect(alert).toHaveTextContent(/Cannot read the repository/i);
    expect(screen.queryByText('Nothing to document')).not.toBeInTheDocument();
  });

  it('distinguishes a completed set that produced nothing', async () => {
    latest.mockResolvedValue(envelope(docSet({ filesScanned: 2 })));
    documents.mockResolvedValue(envelope([]));

    renderPage();

    // Different from the failure above: this one did read the repository and found nothing to say.
    expect(await screen.findByText('Nothing to document')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('surfaces an unreadable repository when generating', async () => {
    latest.mockRejectedValue(notFound());
    const error = new AxiosError('503');
    error.response = {
      status: 503,
      statusText: 'Service Unavailable',
      headers: {},
      config: { headers: new AxiosHeaders() },
      data: { message: 'Cannot read the repository right now. Please try again.' },
    };
    generate.mockRejectedValue(error);

    renderPage();
    await screen.findByText('No documentation yet');
    await userEvent.click(screen.getByRole('button', { name: 'Generate' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/Cannot read the repository/i);
  });

  it('renders document content as text, never as HTML', async () => {
    latest.mockResolvedValue(envelope(docSet()));
    documents.mockResolvedValue(envelope([doc('OVERVIEW', null)]));
    // Repository content is attacker-supplied: a crafted README reaches this page through the
    // generated overview. Rendering it as HTML would make that stored XSS.
    document_.mockResolvedValue(
      envelope(doc('OVERVIEW', '# Overview\n\n<img src=x onerror="alert(1)">')),
    );

    renderPage();
    await userEvent.click(await screen.findByRole('tab', { name: 'Overview' }));

    expect(await screen.findByText(/onerror/)).toBeInTheDocument();
    // The literal text is present; no element was created from it.
    expect(global.document.querySelector('img')).toBeNull();
  });

  it('offers a retry when the document fails to load', async () => {
    latest.mockResolvedValue(envelope(docSet()));
    documents.mockResolvedValue(envelope([doc('OVERVIEW', null)]));
    document_.mockRejectedValue(new Error('boom'));

    renderPage();
    await userEvent.click(await screen.findByRole('tab', { name: 'Overview' }));

    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /try again|retry/i })).toBeInTheDocument();
  });
});
