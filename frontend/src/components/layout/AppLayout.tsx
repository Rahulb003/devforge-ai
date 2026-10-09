import {
  Bell,
  Building2,
  LayoutDashboard,
  LogOut,
  Moon,
  Settings,
  Sparkles,
  Sun,
} from 'lucide-react';
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

export function AppLayout() {
  const { theme, toggleTheme } = useThemeStore();
  const { user, clearAuth } = useAuthStore();
  const navigate = useNavigate();

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
    <div className="min-h-screen bg-slate-950 text-slate-100">
      <div className="flex min-h-screen">
        <aside className="hidden w-72 border-r border-slate-800 bg-slate-900 px-6 py-8 lg:block">
          <div className="mb-10">
            <Link to="/" className="flex items-center gap-3 text-2xl font-semibold text-white">
              <Sparkles className="h-8 w-8 text-indigo-400" aria-hidden="true" />
              DevForge AI
            </Link>
            <p className="mt-2 text-sm text-slate-400">Build. Review. Test. Deploy. Collaborate.</p>
          </div>

          <nav className="space-y-2" aria-label="Main">
            {navItems.map((item) => {
              const Icon = item.icon;
              return (
                <NavLink
                  key={item.path}
                  to={item.path}
                  end={item.end}
                  className={({ isActive }) =>
                    `flex items-center gap-3 rounded-xl px-4 py-3 text-sm font-medium transition-colors ${
                      isActive
                        ? 'bg-slate-800 text-white shadow-sm'
                        : 'text-slate-400 hover:bg-slate-800 hover:text-white'
                    }`
                  }
                >
                  <Icon className="h-5 w-5" aria-hidden="true" />
                  {item.label}
                </NavLink>
              );
            })}
          </nav>
        </aside>

        <main className="flex-1 bg-slate-950">
          <header className="flex items-center justify-between border-b border-slate-800 bg-slate-900 px-6 py-4">
            <div>
              <p className="text-xs uppercase tracking-[0.2em] text-slate-400">Workspace</p>
              <p className="text-lg font-semibold text-white">
                {user ? `${user.firstName} ${user.lastName}` : 'DevForge'}
              </p>
            </div>

            <div className="flex items-center gap-3">
              <NotificationBell />

              <button
                type="button"
                onClick={toggleTheme}
                // Icon-only controls need an explicit name, or a screen reader
                // announces only "button".
                aria-label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
                className="inline-flex h-11 w-11 items-center justify-center rounded-xl border border-slate-700 bg-slate-800 text-slate-200 transition hover:border-slate-600 focus-visible:outline-solid focus-visible:outline-2 focus-visible:outline-indigo-400"
              >
                {theme === 'dark' ? (
                  <Sun className="h-5 w-5" aria-hidden="true" />
                ) : (
                  <Moon className="h-5 w-5" aria-hidden="true" />
                )}
              </button>

              {user && (
                <div className="flex items-center gap-3">
                  <div
                    className="grid h-10 w-10 place-items-center rounded-full bg-indigo-500/15 text-sm font-semibold text-indigo-300"
                    title={user.email}
                  >
                    {initials || user.username.slice(0, 2).toUpperCase()}
                  </div>
                  <button
                    type="button"
                    onClick={handleSignOut}
                    aria-label="Sign out"
                    className="inline-flex h-11 w-11 items-center justify-center rounded-xl border border-slate-700 bg-slate-800 text-slate-300 transition hover:border-slate-600 hover:text-white focus-visible:outline-solid focus-visible:outline-2 focus-visible:outline-indigo-400"
                  >
                    <LogOut className="h-5 w-5" aria-hidden="true" />
                  </button>
                </div>
              )}
            </div>
          </header>

          <section className="px-6 py-8">
            <Outlet />
          </section>
        </main>
      </div>
    </div>
  );
}
