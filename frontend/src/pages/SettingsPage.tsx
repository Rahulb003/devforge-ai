import { Moon, Sun } from 'lucide-react';

import { AccessTokensSection } from '@/components/settings/AccessTokensSection';
import { MfaSection } from '@/components/settings/MfaSection';
import { PasswordSection } from '@/components/settings/PasswordSection';
import { ProfileSection } from '@/components/settings/ProfileSection';
import { SessionsSection } from '@/components/settings/SessionsSection';
import { Button } from '@/components/ui/Button';
import { Card, CardHeader } from '@/components/ui/Card';
import { useThemeStore } from '@/stores/themeStore';

export function SettingsPage() {
  const { theme, toggleTheme } = useThemeStore();

  return (
    <div className="space-y-6">
      <header>
        <h1 className="text-2xl font-semibold text-white">Settings</h1>
        <p className="mt-1 text-sm text-slate-400">Your profile, security and preferences.</p>
      </header>

      <ProfileSection />

      <PasswordSection />

      <MfaSection />

      <SessionsSection />

      <AccessTokensSection />

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
