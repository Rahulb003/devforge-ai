import { Component, ErrorInfo, ReactNode } from 'react';

interface ErrorBoundaryState {
  hasError: boolean;
}

interface ErrorBoundaryProps {
  children: ReactNode;
}

export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  constructor(props: ErrorBoundaryProps) {
    super(props);
    this.state = { hasError: false };
  }

  static getDerivedStateFromError(): ErrorBoundaryState {
    return { hasError: true };
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    console.error('Unhandled error in UI:', error, errorInfo);
  }

  render() {
    if (this.state.hasError) {
      return (
        <div className="rounded-3xl border border-red-500 bg-slate-900 p-12 text-center text-white shadow-xl">
          <p className="text-xl font-semibold">Something went wrong.</p>
          <p className="mt-3 text-slate-400">
            Please refresh the application or contact your administrator.
          </p>
        </div>
      );
    }

    return this.props.children;
  }
}
