export function ProjectsPage() {
  return (
    <div className="space-y-6">
      <div className="rounded-3xl border border-slate-800 bg-slate-900 p-8 shadow-lg">
        <h2 className="text-3xl font-semibold text-white">Projects</h2>
        <p className="mt-3 text-slate-400">
          This placeholder page will host project management and repository insights in a later
          phase.
        </p>
      </div>

      <div className="grid gap-6 lg:grid-cols-3">
        {Array.from({ length: 3 }).map((_, index) => (
          <div
            key={index}
            className="rounded-3xl border border-slate-800 bg-slate-900 p-6 shadow-lg"
          >
            <div className="mb-4 flex items-center justify-between">
              <div>
                <p className="text-sm uppercase tracking-[0.24em] text-slate-500">Project</p>
                <p className="mt-1 text-xl font-semibold text-white">Placeholder {index + 1}</p>
              </div>
            </div>
            <p className="text-slate-400">
              Service-level architecture and project insights will appear here once APIs are
              integrated.
            </p>
          </div>
        ))}
      </div>
    </div>
  );
}
