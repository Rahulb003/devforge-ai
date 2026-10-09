import { AlertTriangle, RefreshCw } from 'lucide-react';
import { Component, type ErrorInfo, type ReactNode } from 'react';

interface ErrorBoundaryState {
  hasError: boolean;
  error: Error | null;
  componentStack: string | null;
}

interface ErrorBoundaryProps {
  children: ReactNode;
}

/**
 * Catches render-time failures anywhere below it.
 *
 * <p>In development it shows the actual error and the component stack. The previous version
 * rendered only "Something went wrong. Please refresh or contact your administrator", which is
 * the least actionable thing it could say — a missing QueryClientProvider produced exactly that
 * screen and gave no clue where to look. Production still shows the neutral message, since a
 * stack trace tells an end user nothing and can disclose internals.
 */
export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  constructor(props: ErrorBoundaryProps) {
    super(props);
    this.state = { hasError: false, error: null, componentStack: null };
  }

  static getDerivedStateFromError(error: Error): Partial<ErrorBoundaryState> {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    // Always logged, in every environment: this is the only record of the failure.
    console.error('Unhandled error in UI:', error, errorInfo);
    this.setState({ componentStack: errorInfo.componentStack ?? null });
  }

  private handleReload = () => {
    window.location.reload();
  };

  render() {
    if (!this.state.hasError) {
      return this.props.children;
    }

    const isDev = import.meta.env.DEV;

    return (
      <div className="grid min-h-screen place-items-center bg-slate-950 px-4 py-12">
        <div className="w-full max-w-2xl rounded-3xl border border-red-500/40 bg-slate-900 p-8 shadow-xl">
          <div className="flex items-start gap-4">
            <div className="grid h-12 w-12 shrink-0 place-items-center rounded-full bg-red-500/10 text-red-400">
              <AlertTriangle className="h-6 w-6" aria-hidden="true" />
            </div>
            <div className="min-w-0">
              <h1 className="text-xl font-semibold text-white">Something went wrong</h1>
              <p className="mt-2 text-sm text-slate-400">
                {isDev
                  ? 'The details below are shown because this is a development build.'
                  : 'Please reload the page. If it keeps happening, contact your administrator.'}
              </p>
            </div>
          </div>

          {isDev && this.state.error && (
            <div className="mt-6 space-y-3">
              <div>
                <p className="text-xs font-medium uppercase tracking-wider text-slate-500">Error</p>
                <pre className="mt-1 overflow-x-auto whitespace-pre-wrap rounded-xl border border-slate-800 bg-slate-950 px-4 py-3 text-xs text-red-300">
                  {this.state.error.message}
                </pre>
              </div>

              {this.state.componentStack && (
                <div>
                  <p className="text-xs font-medium uppercase tracking-wider text-slate-500">
                    Component stack
                  </p>
                  <pre className="mt-1 max-h-60 overflow-auto whitespace-pre-wrap rounded-xl border border-slate-800 bg-slate-950 px-4 py-3 text-xs text-slate-400">
                    {this.state.componentStack.trim()}
                  </pre>
                </div>
              )}
            </div>
          )}

          <button
            type="button"
            onClick={this.handleReload}
            className="mt-6 inline-flex h-11 items-center justify-center gap-2 rounded-xl bg-indigo-500 px-5 text-sm font-semibold text-white transition hover:bg-indigo-400 focus-visible:outline-solid focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-400"
          >
            <RefreshCw className="h-4 w-4" aria-hidden="true" />
            Reload
          </button>
        </div>
      </div>
    );
  }
}
