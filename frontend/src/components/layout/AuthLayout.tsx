import { Outlet } from 'react-router-dom';

/** Centred card shell for the unauthenticated pages. */
export function AuthLayout() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-slate-950 px-4 py-12">
      <main className="w-full max-w-md">
        <div className="rounded-3xl border border-slate-800 bg-slate-900 p-8 shadow-xl">
          <Outlet />
        </div>
        <p className="mt-6 text-center text-xs text-slate-400">
          DevForge AI — Build. Review. Test. Deploy. Collaborate.
        </p>
      </main>
    </div>
  );
}
