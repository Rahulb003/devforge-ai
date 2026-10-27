import {
  Bell,
  Building2,
  LayoutDashboard,
  LogOut,
  Moon,
  PanelLeftClose,
  PanelLeftOpen,
  Settings,
  Sun,
} from 'lucide-react';
import { useState } from 'react';
import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom';

import { NotificationBell } from './NotificationBell';

import { authApi } from '@/api/auth.api';
import { useAuthStore } from '@/stores/authStore';
import { useThemeStore } from '@/stores/themeStore';

const navItems = [
  { path: '/', label: 'Dashboard', icon: LayoutDashboard, end: true },
  { path: '/organizations', label: 'Organizations', icon: Building2, end: false },
  { path: '/notifications', label: 'Notifications', icon: Bell, end: false },
  { path: '/settings', label: 'Settings', icon: Settings, end: false },
];

const RAIL_KEY = 'devforge.sidebar.collapsed';

/** A per-browser preference: storage can be missing or refuse writes, and the page must not care. */
function readCollapsed() {
  try {
    return localStorage.getItem(RAIL_KEY) === '1';
  } catch {
    return false;
  }
}

export function AppLayout() {
  const { theme, toggleTheme } = useThemeStore();
  const { user, clearAuth } = useAuthStore();
  const navigate = useNavigate();
  const [collapsed, setCollapsed] = useState(readCollapsed);

  function toggleRail() {
    setCollapsed((was) => {
      try {
        localStorage.setItem(RAIL_KEY, was ? '0' : '1');
      } catch {
        // Not remembered, which is all that is lost.
      }
      return !was;
    });
  }

  async function handleSignOut() {
    try {
      // Revokes the refresh token server-side. Clearing only local state would
      // leave a usable session behind on the server.
      await authApi.logout();
    } catch {
      // Already-expired sessions make this fail, which must not trap the user
      // in a signed-in shell. Local state is cleared either way.
    } finally {
      clearAuth();
      navigate('/login', { replace: true });
    }
  }

  const initials = user
    ? `${user.firstName?.[0] ?? ''}${user.lastName?.[0] ?? ''}`.toUpperCase()
    : '';

  return (
    <div className="relative min-h-screen text-slate-100">
      <div className="forge-backdrop" aria-hidden="true">
        <div
          className="forge-glow"
          style={{
            top: -220,
            left: '18%',
            width: 640,
            height: 420,
            background: 'var(--forge-glow-a)',
          }}
        />
        <div
          className="forge-glow"
          style={{
            top: -140,
            right: '4%',
            width: 520,
            height: 380,
            background: 'var(--forge-glow-b)',
            animationDirection: 'reverse',
            animationDuration: '28s',
          }}
        />
      </div>

      <div className="flex min-h-screen">
        <aside
          className={`forge-surface sticky top-0 hidden h-screen shrink-0 flex-col border-r py-6 transition-[width] duration-300 md:flex ${
            collapsed ? 'w-[76px] items-center px-3' : 'w-64 px-4'
          }`}
        >
          <Link
            to="/"
            aria-label="DevForge AI home"
            className={`mb-8 flex items-center gap-3 rounded-2xl ${collapsed ? '' : 'px-2'}`}
          >
            <span className="grid h-11 w-11 shrink-0 place-items-center rounded-2xl bg-linear-to-br from-forge-400 to-indigo-500 font-display text-xl font-bold text-on-forge">
              F
            </span>
            {!collapsed && (
              <span className="font-display text-xl font-semibold text-white">DevForge</span>
            )}
          </Link>

          <nav className="flex w-full flex-col gap-1.5" aria-label="Main">
            {navItems.map((item) => {
              const Icon = item.icon;
              return (
                <NavLink
                  key={item.path}
                  to={item.path}
                  end={item.end}
                  // Collapsed, the label is gone from the screen but not from the link: the
                  // accessible name stays the same either way.
                  aria-label={collapsed ? item.label : undefined}
                  title={collapsed ? item.label : undefined}
                  className={({ isActive }) =>
                    `flex h-11 items-center gap-3 rounded-xl text-sm font-medium transition-colors ${
                      collapsed ? 'justify-center' : 'px-3'
                    } ${
                      isActive
                        ? 'bg-indigo-500/15 text-indigo-200 shadow-[inset_3px_0_0_var(--color-indigo-400)]'
                        : 'text-slate-400 hover:bg-slate-800/60 hover:text-white'
                    }`
                  }
                >
                  <Icon className="h-5 w-5 shrink-0" aria-hidden="true" />
                  {!collapsed && item.label}
                </NavLink>
              );
            })}
          </nav>

          <div className="flex-1" />
          <button
            type="button"
            onClick={toggleRail}
            aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
            aria-expanded={!collapsed}
            className={`flex h-11 items-center gap-3 rounded-xl text-sm text-slate-400 transition hover:bg-slate-800/60 hover:text-white focus-visible:outline-solid focus-visible:outline-2 focus-visible:outline-indigo-400 ${
              collapsed ? 'w-11 justify-center' : 'px-3'
            }`}
          >
            {collapsed ? (
              <PanelLeftOpen className="h-5 w-5" aria-hidden="true" />
            ) : (
              <>
                <PanelLeftClose className="h-5 w-5" aria-hidden="true" />
                Collapse
              </>
            )}
          </button>
        </aside>

        <main className="min-w-0 flex-1">
          <header className="forge-surface sticky top-0 z-10 flex items-center justify-between gap-3 border-b px-4 py-3 sm:px-6">
            <div className="min-w-0">
              <p className="font-mono text-[11px] tracking-[0.2em] text-slate-400 uppercase">
                Workspace
              </p>
              <p className="truncate font-display text-lg font-semibold text-white">
                {user ? `${user.firstName} ${user.lastName}` : 'DevForge'}
              </p>
            </div>

            <div className="flex items-center gap-2 sm:gap-3">
              <NotificationBell />

              <button
                type="button"
                onClick={toggleTheme}
                // Icon-only controls need an explicit name, or a screen reader
                // announces only "button".
                aria-label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
                className="inline-flex h-11 w-11 items-center justify-center rounded-xl border border-slate-700/70 bg-slate-800/60 text-slate-200 transition hover:border-slate-600 focus-visible:outline-solid focus-visible:outline-2 focus-visible:outline-indigo-400"
              >
                {theme === 'dark' ? (
                  <Sun className="h-5 w-5" aria-hidden="true" />
                ) : (
                  <Moon className="h-5 w-5" aria-hidden="true" />
                )}
              </button>

              {user && (
                <div className="flex items-center gap-2 sm:gap-3">
                  <div
                    className="grid h-10 w-10 place-items-center rounded-full bg-indigo-500/20 text-sm font-semibold text-indigo-200 ring-2 ring-indigo-400/30"
                    title={user.email}
                  >
                    {initials || user.username.slice(0, 2).toUpperCase()}
                  </div>
                  <button
                    type="button"
                    onClick={handleSignOut}
                    aria-label="Sign out"
                    className="inline-flex h-11 w-11 items-center justify-center rounded-xl border border-slate-700/70 bg-slate-800/60 text-slate-300 transition hover:border-slate-600 hover:text-white focus-visible:outline-solid focus-visible:outline-2 focus-visible:outline-indigo-400"
                  >
                    <LogOut className="h-5 w-5" aria-hidden="true" />
                  </button>
                </div>
              )}
            </div>
          </header>

          <nav
            aria-label="Sections"
            className="forge-surface flex gap-1 overflow-x-auto border-b px-3 py-2 md:hidden"
          >
            {navItems.map((item) => {
              const Icon = item.icon;
              return (
                <NavLink
                  key={item.path}
                  to={item.path}
                  end={item.end}
                  className={({ isActive }) =>
                    `flex h-11 shrink-0 items-center gap-2 rounded-xl px-3 text-sm font-medium ${
                      isActive ? 'bg-indigo-500/15 text-indigo-200' : 'text-slate-400'
                    }`
                  }
                >
                  <Icon className="h-4 w-4" aria-hidden="true" />
                  {item.label}
                </NavLink>
              );
            })}
          </nav>

          <section className="px-4 py-8 sm:px-6 lg:px-10">
            <Outlet />
          </section>
        </main>
      </div>
    </div>
  );
}
