import { Moon, Sun } from 'lucide-react';

import { MfaSection } from '@/components/settings/MfaSection';
import { SessionsSection } from '@/components/settings/SessionsSection';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { Card, CardHeader } from '@/components/ui/Card';
import { useAuthStore } from '@/stores/authStore';
import { useThemeStore } from '@/stores/themeStore';

export function SettingsPage() {
  const user = useAuthStore((s) => s.user);
  const { theme, toggleTheme } = useThemeStore();

  return (
    <div className="space-y-6">
      <header>
        <h1 className="text-2xl font-semibold text-white">Settings</h1>
        <p className="mt-1 text-sm text-slate-400">Your profile, security and preferences.</p>
      </header>

      <Card>
        <CardHeader title="Profile" description="Details from your account." />
        <dl className="grid gap-4 sm:grid-cols-2">
          <div>
            <dt className="text-sm text-slate-400">Name</dt>
            <dd className="mt-1 text-slate-200">
              {user ? `${user.firstName} ${user.lastName}` : '—'}
            </dd>
          </div>
          <div>
            <dt className="text-sm text-slate-400">Username</dt>
            <dd className="mt-1 text-slate-200">{user?.username ?? '—'}</dd>
          </div>
          <div>
            <dt className="text-sm text-slate-400">Email</dt>
            <dd className="mt-1 flex items-center gap-2 text-slate-200">
              {user?.email ?? '—'}
              {user && (
                <Badge tone={user.emailVerified ? 'success' : 'warning'}>
                  {user.emailVerified ? 'Verified' : 'Unverified'}
                </Badge>
              )}
            </dd>
          </div>
          <div>
            <dt className="text-sm text-slate-400">Roles</dt>
            <dd className="mt-1 text-slate-200">{user?.roles?.join(', ') ?? '—'}</dd>
          </div>
        </dl>
        <p className="mt-5 text-xs text-slate-400">
          Editing your profile is not built yet — the backend has no update endpoint for it.
        </p>
      </Card>

      <MfaSection />

      <SessionsSection />

      <Card>
        <CardHeader title="Appearance" description="Applies to this browser only." />
        <Button
          variant="secondary"
          leftIcon={theme === 'dark' ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
          onClick={toggleTheme}
        >
          Switch to {theme === 'dark' ? 'light' : 'dark'} theme
        </Button>
      </Card>
    </div>
  );
}
