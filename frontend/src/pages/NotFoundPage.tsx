import { Link } from 'react-router-dom';

export function NotFoundPage() {
  return (
    <div className="grid min-h-[65vh] place-items-center">
      <div className="rounded-3xl border border-slate-800 bg-slate-900 p-12 text-center shadow-xl">
        <p className="text-sm uppercase tracking-[0.22em] text-slate-500">404 error</p>
        <h1 className="mt-4 text-5xl font-semibold text-white">Page not found</h1>
        <p className="mt-4 text-slate-400">
          The route you followed does not exist yet. Return to the dashboard to continue.
        </p>
        <Link
          to="/"
          className="mt-8 inline-flex rounded-xl bg-indigo-500 px-6 py-3 text-sm font-semibold text-white transition hover:bg-indigo-400"
        >
          Go back to dashboard
        </Link>
      </div>
    </div>
  );
}
