import type { ElementType, HTMLAttributes, ReactNode } from 'react';

import { cn } from '@/lib/utils';

/**
 * Surface container.
 *
 * `as` exists so a card that is a distinct region of a page can render as a
 * landmark element rather than a div, which gives it a name in the
 * accessibility tree instead of being an anonymous group.
 */
export function Card({
  className,
  as: Component = 'div',
  ...props
}: HTMLAttributes<HTMLElement> & { as?: ElementType }) {
  return (
    <Component
      className={cn('rounded-2xl border border-slate-800 bg-slate-900 p-6 shadow-xs', className)}
      {...props}
    />
  );
}

export function CardHeader({
  title,
  description,
  action,
}: {
  title: string;
  description?: string;
  action?: ReactNode;
}) {
  return (
    <div className="mb-5 flex items-start justify-between gap-4">
      <div>
        <h2 className="text-lg font-semibold text-white">{title}</h2>
        {description && <p className="mt-1 text-sm text-slate-400">{description}</p>}
      </div>
      {action}
    </div>
  );
}
