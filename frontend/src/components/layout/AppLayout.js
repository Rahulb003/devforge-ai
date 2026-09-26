import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
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
    return (_jsx("div", { className: `min-h-screen bg-slate-950 text-slate-100 ${theme === 'dark' ? 'dark' : ''}`, children: _jsxs("div", { className: "flex min-h-screen", children: [_jsxs("aside", { className: "w-72 border-r border-slate-800 bg-slate-900 px-6 py-8 hidden lg:block", children: [_jsxs("div", { className: "mb-10", children: [_jsxs(Link, { to: "/", className: "flex items-center gap-3 text-2xl font-semibold text-white", children: [_jsx(Sparkles, { className: "h-8 w-8 text-indigo-400" }), "DevForge AI"] }), _jsx("p", { className: "mt-2 text-sm text-slate-400", children: "Build. Review. Test. Deploy. Collaborate." })] }), _jsx("nav", { className: "space-y-2", children: navItems.map((item) => {
                                const Icon = item.icon;
                                return (_jsxs(NavLink, { to: item.path, className: ({ isActive }) => `flex items-center gap-3 rounded-xl px-4 py-3 text-sm font-medium transition-colors ${isActive
                                        ? 'bg-slate-800 text-white shadow'
                                        : 'text-slate-400 hover:bg-slate-800 hover:text-white'}`, children: [_jsx(Icon, { className: "h-5 w-5" }), item.label] }, item.path));
                            }) })] }), _jsxs("main", { className: "flex-1 bg-slate-950", children: [_jsxs("header", { className: "flex items-center justify-between border-b border-slate-800 bg-slate-900 px-6 py-4", children: [_jsxs("div", { children: [_jsx("p", { className: "text-xs uppercase tracking-[0.2em] text-slate-500", children: "Workspace" }), _jsx("p", { className: "text-lg font-semibold text-white", children: location.pathname === '/' ? 'Dashboard' : location.pathname.slice(1) })] }), _jsx("div", { className: "flex items-center gap-3", children: _jsx("button", { type: "button", onClick: toggleTheme, className: "inline-flex h-11 w-11 items-center justify-center rounded-xl border border-slate-700 bg-slate-800 text-slate-200 transition hover:border-slate-600", children: theme === 'dark' ? _jsx(Sun, { className: "h-5 w-5" }) : _jsx(Moon, { className: "h-5 w-5" }) }) })] }), _jsx("section", { className: "px-6 py-8", children: _jsx(Outlet, {}) })] })] }) }));
}
