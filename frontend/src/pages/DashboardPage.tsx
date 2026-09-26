export function DashboardPage() {
  return (
    <div className="space-y-6">
      <section className="rounded-3xl border border-slate-800 bg-slate-900 p-8 shadow-lg">
        <h1 className="text-3xl font-semibold text-white">Welcome to DevForge AI</h1>
        <p className="mt-3 text-slate-400 max-w-2xl">
          Phase 0 foundation is ready. This screen is a shell placeholder for the future enterprise
          AI developer workspace.
        </p>
      </section>

      <section className="grid gap-6 lg:grid-cols-2">
        <div className="rounded-3xl border border-slate-800 bg-slate-900 p-6 shadow-lg">
          <p className="text-sm uppercase tracking-[0.24em] text-slate-500">Workspace status</p>
          <div className="mt-6 text-5xl font-semibold text-white">Healthy</div>
          <p className="mt-4 text-slate-400">
            Health endpoints and shell UI are configured. Backend services are prepared for
            microservice integration.
          </p>
        </div>

        <div className="rounded-3xl border border-slate-800 bg-slate-900 p-6 shadow-lg">
          <p className="text-sm uppercase tracking-[0.24em] text-slate-500">Next steps</p>
          <ul className="mt-6 space-y-2 text-slate-300">
            <li>• Add backend service contracts</li>
            <li>• Implement shared configuration and discovery</li>
            <li>• Build service-specific feature modules</li>
          </ul>
        </div>
      </section>
    </div>
  );
}
