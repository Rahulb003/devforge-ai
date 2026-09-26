import { Eye, EyeOff } from 'lucide-react';
import { forwardRef, useId, useState, type InputHTMLAttributes } from 'react';

import { cn } from '@/lib/utils';

interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  label: string;
  error?: string;
  hint?: string;
}

/**
 * Labelled text input.
 *
 * The label is always rendered and tied to the control with a generated id:
 * placeholder-only fields disappear once typing starts and are unreadable to a
 * screen reader. Errors are linked with aria-describedby and aria-invalid so
 * the failure is announced rather than only shown in red.
 */
export const Input = forwardRef<HTMLInputElement, InputProps>(function Input(
  { label, error, hint, className, id, type = 'text', ...props },
  ref,
) {
  const generatedId = useId();
  const inputId = id ?? generatedId;
  const errorId = `${inputId}-error`;
  const hintId = `${inputId}-hint`;

  const [revealed, setRevealed] = useState(false);
  const isPassword = type === 'password';
  const effectiveType = isPassword && revealed ? 'text' : type;

  return (
    <div className="space-y-1.5">
      <label htmlFor={inputId} className="block text-sm font-medium text-slate-300">
        {label}
      </label>

      <div className="relative">
        <input
          ref={ref}
          id={inputId}
          type={effectiveType}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? errorId : hint ? hintId : undefined}
          className={cn(
            'w-full rounded-xl border bg-slate-900 px-4 py-3 text-sm text-slate-100',
            'placeholder:text-slate-500',
            'focus:outline focus:outline-2 focus:outline-offset-2',
            error
              ? 'border-red-500/70 focus:outline-red-400'
              : 'border-slate-700 focus:border-slate-600 focus:outline-indigo-400',
            isPassword && 'pr-12',
            className,
          )}
          {...props}
        />

        {isPassword && (
          <button
            type="button"
            onClick={() => setRevealed((v) => !v)}
            // Not a form control: excluded from tab order would hide it from
            // keyboard users, so it stays focusable with a real accessible name.
            aria-label={revealed ? 'Hide password' : 'Show password'}
            className="absolute right-3 top-1/2 -translate-y-1/2 rounded-lg p-1 text-slate-400 transition hover:text-slate-200 focus-visible:outline focus-visible:outline-2 focus-visible:outline-indigo-400"
          >
            {revealed ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
          </button>
        )}
      </div>

      {error ? (
        <p id={errorId} role="alert" className="text-sm text-red-400">
          {error}
        </p>
      ) : hint ? (
        <p id={hintId} className="text-sm text-slate-500">
          {hint}
        </p>
      ) : null}
    </div>
  );
});
