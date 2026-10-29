import { useQueries, useQuery } from '@tanstack/react-query';
import {
  Bell,
  Building2,
  FolderKanban,
  LayoutDashboard,
  Search,
  Settings,
  SunMoon,
} from 'lucide-react';
import type { ComponentType } from 'react';
import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { projectApi } from '@/api/project.api';
import { useThemeStore } from '@/stores/themeStore';

interface Command {
  id: string;
  group: 'Actions' | 'Pages' | 'Organizations' | 'Projects';
  label: string;
  hint: string;
  icon: ComponentType<{ className?: string }>;
  run: () => void;
}

const MAX_RESULTS = 50;

/**
 * How well a command matches: the label starting with the query beats a word in it starting with
 * the query, which beats the query anywhere in the label, which beats a hint-only match. Typing
 * "ca" meant Calculator, not the "ca" inside Notifications.
 */
function matchScore(c: Command, q: string) {
  const label = c.label.toLowerCase();
  if (label.startsWith(q)) return 4;
  if (label.split(/[\s/·-]+/).some((word) => word.startsWith(q))) return 3;
  if (label.includes(q)) return 2;
  return c.hint.toLowerCase().includes(q) ? 1 : 0;
}

/**
 * Jump anywhere, or run an action, from the keyboard.
 *
 * Only real destinations: the app's pages, and the organizations and projects the viewer belongs
 * to, read when the palette opens - the same lists the pages themselves show, so it cannot offer a
 * project the viewer would then be refused.
 *
 * The ARIA combobox pattern: focus stays in the input, arrow keys move aria-activedescendant
 * through the listbox, Enter runs the active option, Escape closes and returns focus to whatever
 * opened it.
 */
export function CommandPalette({ open, onClose }: { open: boolean; onClose: () => void }) {
  const navigate = useNavigate();
  const { theme, toggleTheme } = useThemeStore();
  const [query, setQuery] = useState('');
  const [active, setActive] = useState(0);
  const input = useRef<HTMLInputElement>(null);
  const opener = useRef<HTMLElement | null>(null);
  const listId = useId();

  const organizations = useQuery({
    queryKey: ['organizations'],
    queryFn: async () => (await projectApi.listOrganizations()).data.data,
    enabled: open,
  });
  const projects = useQueries({
    queries: (organizations.data ?? []).map((org) => ({
      queryKey: ['projects', org.id, 'palette'],
      queryFn: async () => (await projectApi.listProjects(org.id, 0, 50)).data.data.content,
      enabled: open,
    })),
  });

  useEffect(() => {
    if (!open) return;
    opener.current = document.activeElement as HTMLElement | null;
    setQuery('');
    setActive(0);
    input.current?.focus();
    return () => opener.current?.focus();
  }, [open]);

  const commands = useMemo<Command[]>(() => {
    const go = (path: string) => () => {
      onClose();
      navigate(path);
    };
    const list: Command[] = [
      {
        id: 'theme',
        group: 'Actions',
        label: theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme',
        hint: 'Appearance',
        icon: SunMoon,
        run: () => {
          toggleTheme();
          onClose();
        },
      },
      {
        id: 'p-home',
        group: 'Pages',
        label: 'Dashboard',
        hint: 'Your day',
        icon: LayoutDashboard,
        run: go('/'),
      },
      {
        id: 'p-orgs',
        group: 'Pages',
        label: 'Organizations',
        hint: 'All of yours',
        icon: Building2,
        run: go('/organizations'),
      },
      {
        id: 'p-notes',
        group: 'Pages',
        label: 'Notifications',
        hint: 'Your feed',
        icon: Bell,
        run: go('/notifications'),
      },
      {
        id: 'p-settings',
        group: 'Pages',
        label: 'Settings',
        hint: 'Profile and security',
        icon: Settings,
        run: go('/settings'),
      },
    ];
    for (const org of organizations.data ?? []) {
      list.push({
        id: 'o-' + org.id,
        group: 'Organizations',
        label: org.name,
        hint: 'Organization · ' + org.role.toLowerCase(),
        icon: Building2,
        run: go(`/organizations/${org.id}`),
      });
    }
    projects.forEach((result, i) => {
      const org = organizations.data?.[i];
      for (const project of result.data ?? []) {
        list.push({
          id: 'pr-' + project.id,
          group: 'Projects',
          label: project.name,
          hint: `${project.projectKey} · ${org?.name ?? ''}`,
          icon: FolderKanban,
          run: go(`/organizations/${project.organizationId}/projects/${project.id}`),
        });
      }
    });
    return list;
  }, [organizations.data, projects, theme, toggleTheme, navigate, onClose]);

  const results = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return commands.slice(0, MAX_RESULTS);
    const scored = commands
      .map((c, order) => ({ c, order, score: matchScore(c, q) }))
      .filter((x) => x.score > 0);
    // Groups stay together, ordered by their best match; within a group, best match first.
    const best = new Map<string, number>();
    for (const x of scored) best.set(x.c.group, Math.max(best.get(x.c.group) ?? 0, x.score));
    scored.sort((a, b) => {
      const byGroup = (best.get(b.c.group) ?? 0) - (best.get(a.c.group) ?? 0);
      if (byGroup !== 0) return byGroup;
      if (a.c.group !== b.c.group) return a.order - b.order;
      return b.score - a.score || a.order - b.order;
    });
    return scored.map((x) => x.c).slice(0, MAX_RESULTS);
  }, [commands, query]);

  const current = Math.min(active, Math.max(results.length - 1, 0));

  if (!open) return null;

  function onKeyDown(event: React.KeyboardEvent) {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setActive((current + 1) % Math.max(results.length, 1));
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      setActive((current - 1 + results.length) % Math.max(results.length, 1));
    } else if (event.key === 'Enter') {
      event.preventDefault();
      results[current]?.run();
    } else if (event.key === 'Escape') {
      event.preventDefault();
      onClose();
    }
  }

  let lastGroup = '';
  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center px-4 pt-[12vh]">
      {/* Clicking outside closes. Out of the tab order: Escape is the keyboard's way out. */}
      <button
        type="button"
        tabIndex={-1}
        aria-label="Close command palette"
        onClick={onClose}
        className="absolute inset-0 cursor-default bg-slate-950/60 backdrop-blur-sm"
      />
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Command palette"
        className="forge-surface relative w-full max-w-xl overflow-hidden rounded-3xl border shadow-2xl"
      >
        <div className="flex items-center gap-3 border-b border-slate-800 px-5 py-4">
          <Search className="h-5 w-5 shrink-0 text-indigo-300" aria-hidden="true" />
          <input
            ref={input}
            role="combobox"
            onKeyDown={onKeyDown}
            aria-expanded="true"
            aria-controls={listId}
            aria-activedescendant={
              results[current] ? `${listId}-${results[current].id}` : undefined
            }
            aria-label="Search pages, organizations and projects"
            value={query}
            onChange={(e) => {
              setQuery(e.target.value);
              setActive(0);
            }}
            placeholder="Search pages, organizations and projects…"
            autoComplete="off"
            className="h-8 min-w-0 flex-1 bg-transparent text-base text-white outline-none placeholder:text-slate-400"
          />
          <kbd className="rounded-md border border-slate-700 px-2 py-0.5 font-mono text-xs text-slate-300">
            Esc
          </kbd>
        </div>
        <ul
          id={listId}
          role="listbox"
          aria-label="Results"
          className="max-h-[50vh] overflow-y-auto p-2"
        >
          {results.map((c, i) => {
            const heading = c.group !== lastGroup ? c.group : null;
            lastGroup = c.group;
            const Icon = c.icon;
            return (
              <li key={c.id} role="presentation">
                {heading && (
                  <p
                    role="presentation"
                    className="px-3 pt-3 pb-1.5 text-[11px] font-semibold tracking-[0.12em] text-slate-400 uppercase"
                  >
                    {heading}
                  </p>
                )}
                <div
                  id={`${listId}-${c.id}`}
                  role="option"
                  aria-selected={i === current}
                  // Reached through the input's aria-activedescendant, not by tabbing; the key handler
                  // only matters if something focuses an option directly.
                  tabIndex={-1}
                  onMouseMove={() => setActive(i)}
                  onClick={c.run}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') c.run();
                  }}
                  className={`flex cursor-pointer items-center gap-3 rounded-xl px-3 py-2.5 ${
                    i === current ? 'bg-indigo-500/15 text-white' : 'text-slate-200'
                  }`}
                >
                  <span className="grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-indigo-500/10 text-indigo-300">
                    <Icon className="h-4 w-4" aria-hidden="true" />
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm font-medium">{c.label}</span>
                    <span className="block truncate text-xs text-slate-400">{c.hint}</span>
                  </span>
                </div>
              </li>
            );
          })}
          {results.length === 0 && (
            <li role="presentation" className="px-3 py-8 text-center text-sm text-slate-400">
              Nothing matches “{query}”.
            </li>
          )}
        </ul>
        <div className="flex flex-wrap gap-4 border-t border-slate-800 px-5 py-2.5 text-xs text-slate-400">
          <span>↑ ↓ to move</span>
          <span>Enter to open</span>
          <span>Ctrl K to toggle</span>
        </div>
      </div>
    </div>
  );
}
