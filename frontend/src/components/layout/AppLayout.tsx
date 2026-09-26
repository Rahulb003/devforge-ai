import { Link, NavLink, Outlet, useLocation } from 'react-router-dom';
import { Moon, Sun, LayoutDashboard, Settings, Folder, Sparkles } from 'lucide-react';

import { useThemeStore } from '../../stores/themeStore';

const navItems = [
  { path: '/', label: 'Dashboard', icon: LayoutDashboard },
  { path: '/projects', label: 'Projects', icon: Folder },
  { path: '/settings', label: 'Settings', icon: Settings },
];

export function AppLayout() {
  const { theme, toggleTheme } = useThemeStore();
  const location = useLocation();

  return (
    <div className={`min-h-screen bg-slate-950 text-slate-100 ${theme === 'dark' ? 'dark' : ''}`}>
      <div className="flex min-h-screen">
        <aside className="w-72 border-r border-slate-800 bg-slate-900 px-6 py-8 hidden lg:block">
          <div className="mb-10">
            <Link to="/" className="flex items-center gap-3 text-2xl font-semibold text-white">
              <Sparkles className="h-8 w-8 text-indigo-400" />
              DevForge AI
            </Link>
            <p className="mt-2 text-sm text-slate-400">Build. Review. Test. Deploy. Collaborate.</p>
          </div>
          <nav className="space-y-2">
            {navItems.map((item) => {
              const Icon = item.icon;
              return (
                <NavLink
                  key={item.path}
                  to={item.path}
                  className={({ isActive }) =>
                    `flex items-center gap-3 rounded-xl px-4 py-3 text-sm font-medium transition-colors ${
                      isActive
                        ? 'bg-slate-800 text-white shadow'
                        : 'text-slate-400 hover:bg-slate-800 hover:text-white'
                    }`
                  }
                >
                  <Icon className="h-5 w-5" />
                  {item.label}
                </NavLink>
              );
            })}
          </nav>
        </aside>

        <main className="flex-1 bg-slate-950">
          <header className="flex items-center justify-between border-b border-slate-800 bg-slate-900 px-6 py-4">
            <div>
              <p className="text-xs uppercase tracking-[0.2em] text-slate-500">Workspace</p>
              <p className="text-lg font-semibold text-white">
                {location.pathname === '/' ? 'Dashboard' : location.pathname.slice(1)}
              </p>
            </div>
            <div className="flex items-center gap-3">
              <button
                type="button"
                onClick={toggleTheme}
                className="inline-flex h-11 w-11 items-center justify-center rounded-xl border border-slate-700 bg-slate-800 text-slate-200 transition hover:border-slate-600"
              >
                {theme === 'dark' ? <Sun className="h-5 w-5" /> : <Moon className="h-5 w-5" />}
              </button>
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
