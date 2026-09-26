import { Loader2 } from 'lucide-react';
import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react';

import { cn } from '@/lib/utils';

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger';
type Size = 'sm' | 'md' | 'lg';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
  /** Shows a spinner and blocks input. Use for in-flight requests. */
  loading?: boolean;
  leftIcon?: ReactNode;
  fullWidth?: boolean;
}

const variants: Record<Variant, string> = {
  primary: 'bg-indigo-500 text-white hover:bg-indigo-400 focus-visible:outline-indigo-400',
  secondary:
    'border border-slate-700 bg-slate-800 text-slate-100 hover:border-slate-600 hover:bg-slate-750 focus-visible:outline-slate-500',
  ghost: 'text-slate-300 hover:bg-slate-800 hover:text-white focus-visible:outline-slate-500',
  danger: 'bg-red-600 text-white hover:bg-red-500 focus-visible:outline-red-400',
};

const sizes: Record<Size, string> = {
  sm: 'h-9 px-3 text-sm',
  md: 'h-11 px-5 text-sm',
  lg: 'h-12 px-6 text-base',
};

/**
 * The single button in the app.
 *
 * `loading` also sets `disabled`, so a double submit cannot fire a second
 * request while the first is in flight — the usual cause of duplicate signups.
 * `aria-busy` exposes the same state to assistive technology.
 */
export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  {
    variant = 'primary',
    size = 'md',
    loading = false,
    leftIcon,
    fullWidth,
    className,
    children,
    disabled,
    ...props
  },
  ref,
) {
  return (
    <button
      ref={ref}
      disabled={disabled || loading}
      aria-busy={loading}
      className={cn(
        'inline-flex items-center justify-center gap-2 rounded-xl font-semibold transition-colors',
        'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2',
        'disabled:cursor-not-allowed disabled:opacity-60',
        variants[variant],
        sizes[size],
        fullWidth && 'w-full',
        className,
      )}
      {...props}
    >
      {loading ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" /> : leftIcon}
      {children}
    </button>
  );
});
