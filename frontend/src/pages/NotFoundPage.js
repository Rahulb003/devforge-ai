import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { Link } from 'react-router-dom';
export function NotFoundPage() {
    return (_jsx("div", { className: "grid min-h-[65vh] place-items-center", children: _jsxs("div", { className: "rounded-3xl border border-slate-800 bg-slate-900 p-12 text-center shadow-xl", children: [_jsx("p", { className: "text-sm uppercase tracking-[0.22em] text-slate-500", children: "404 error" }), _jsx("h1", { className: "mt-4 text-5xl font-semibold text-white", children: "Page not found" }), _jsx("p", { className: "mt-4 text-slate-400", children: "The route you followed does not exist yet. Return to the dashboard to continue." }), _jsx(Link, { to: "/", className: "mt-8 inline-flex rounded-xl bg-indigo-500 px-6 py-3 text-sm font-semibold text-white transition hover:bg-indigo-400", children: "Go back to dashboard" })] }) }));
}
