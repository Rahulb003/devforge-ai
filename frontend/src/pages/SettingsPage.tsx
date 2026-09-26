export function SettingsPage() {
  return (
    <div className="space-y-6">
      <div className="rounded-3xl border border-slate-800 bg-slate-900 p-8 shadow-lg">
        <h2 className="text-3xl font-semibold text-white">Settings</h2>
        <p className="mt-3 text-slate-400">
          This settings placeholder is ready for platform preferences, environment configuration,
          and access control settings.
        </p>
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        <div className="rounded-3xl border border-slate-800 bg-slate-900 p-6 shadow-lg">
          <p className="text-slate-300">
            Theme switching and UI preferences are implemented at the shell level.
          </p>
        </div>
        <div className="rounded-3xl border border-slate-800 bg-slate-900 p-6 shadow-lg">
          <p className="text-slate-300">
            Platform configuration and environment settings will be connected in future foundation
            phases.
          </p>
        </div>
      </div>
    </div>
  );
}
