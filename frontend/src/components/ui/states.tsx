import { AlertTriangle, Loader2, RefreshCw } from 'lucide-react';
import type { ReactNode } from 'react';

import { Button } from './Button';

import { cn } from '@/lib/utils';

/** Spinner for a region that is still loading. */
export function LoadingState({
  label = 'Loading…',
  className,
}: {
  label?: string;
  className?: string;
}) {
  return (
    <div
      className={cn(
        'flex flex-col items-center justify-center gap-3 py-16 text-slate-400',
        className,
      )}
      role="status"
      aria-live="polite"
    >
      <Loader2 className="h-6 w-6 animate-spin" aria-hidden="true" />
      <p className="text-sm">{label}</p>
    </div>
  );
}

/** Placeholder block that mirrors the shape of the content it replaces. */
export function Skeleton({ className }: { className?: string }) {
  return (
    <div className={cn('animate-pulse rounded-xl bg-slate-800', className)} aria-hidden="true" />
  );
}

/**
 * Failure state with a retry.
 *
 * Always offers a way forward: a dead end leaves the user reloading the page,
 * which loses any unsaved work elsewhere on it.
 */
export function ErrorState({
  title = 'Something went wrong',
  message,
  onRetry,
}: {
  title?: string;
  message?: string;
  onRetry?: () => void;
}) {
  return (
    <div className="flex flex-col items-center justify-center gap-4 py-16 text-center" role="alert">
      <div className="grid h-12 w-12 place-items-center rounded-full bg-red-500/10 text-red-400">
        <AlertTriangle className="h-6 w-6" aria-hidden="true" />
      </div>
      <div>
        <h2 className="text-lg font-semibold text-white">{title}</h2>
        {message && <p className="mt-1 max-w-md text-sm text-slate-400">{message}</p>}
      </div>
      {onRetry && (
        <Button
          variant="secondary"
          size="sm"
          leftIcon={<RefreshCw className="h-4 w-4" />}
          onClick={onRetry}
        >
          Try again
        </Button>
      )}
    </div>
  );
}

/**
 * Empty state.
 *
 * Answers all three questions an empty screen should: what is missing, why it
 * matters, and what to do next. A bare "No data" answers none of them.
 */
export function EmptyState({
  icon,
  title,
  description,
  action,
}: {
  icon?: ReactNode;
  title: string;
  description: string;
  action?: ReactNode;
}) {
  return (
    <div className="flex flex-col items-center justify-center gap-4 rounded-2xl border border-dashed border-slate-700 bg-slate-900/40 py-16 text-center">
      {icon && (
        <div className="grid h-12 w-12 place-items-center rounded-full bg-slate-800 text-slate-400">
          {icon}
        </div>
      )}
      <div>
        <h2 className="text-lg font-semibold text-white">{title}</h2>
        <p className="mx-auto mt-1 max-w-md text-sm text-slate-400">{description}</p>
      </div>
      {action}
    </div>
  );
}
