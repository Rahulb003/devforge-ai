import { useMutation } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';

import { authApi } from '@/api/auth.api';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { Card, CardHeader } from '@/components/ui/Card';
import { Input } from '@/components/ui/Input';
import { describeApiError } from '@/lib/errors';
import { useAuthStore } from '@/stores/authStore';

/** Every zone the browser knows, so the field suggests real ones; the server validates anyway. */
function timeZones(): string[] {
  const intl = Intl as unknown as { supportedValuesOf?: (key: string) => string[] };
  return intl.supportedValuesOf?.('timeZone') ?? [];
}

/**
 * The editable part of the profile. Username and email are shown but not editable here: changing
 * either is an identity change (sign-in name, recovery address) that needs its own verification.
 */
export function ProfileSection() {
  const user = useAuthStore((s) => s.user);
  const updateUser = useAuthStore((s) => s.updateUser);
  const [firstName, setFirstName] = useState(user?.firstName ?? '');
  const [lastName, setLastName] = useState(user?.lastName ?? '');
  const [timezone, setTimezone] = useState(user?.timezone ?? '');
  const [language, setLanguage] = useState(user?.language ?? '');
  const [saved, setSaved] = useState(false);

  const save = useMutation({
    mutationFn: async () =>
      (
        await authApi.updateProfile({
          firstName,
          lastName,
          // Blank means "leave as it is", not "clear it".
          ...(timezone.trim() ? { timezone: timezone.trim() } : {}),
          ...(language.trim() ? { language: language.trim() } : {}),
        })
      ).data.data,
    onSuccess: (profile) => {
      updateUser(profile);
      setSaved(true);
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    setSaved(false);
    save.mutate();
  }

  return (
    <Card>
      <CardHeader title="Profile" description="How you appear to your teams." />
      <form onSubmit={submit} aria-label="Profile" className="space-y-4">
        <div className="grid gap-4 sm:grid-cols-2">
          <Input
            label="First name"
            value={firstName}
            maxLength={100}
            onChange={(e) => setFirstName(e.target.value)}
          />
          <Input
            label="Last name"
            value={lastName}
            maxLength={100}
            onChange={(e) => setLastName(e.target.value)}
          />
          <Input
            label="Time zone"
            placeholder="e.g. Europe/Berlin"
            list="time-zones"
            value={timezone}
            onChange={(e) => setTimezone(e.target.value)}
          />
          <datalist id="time-zones">
            {timeZones().map((zone) => (
              <option key={zone} value={zone} />
            ))}
          </datalist>
          <Input
            label="Language"
            placeholder="e.g. en or en-GB"
            maxLength={5}
            value={language}
            onChange={(e) => setLanguage(e.target.value)}
          />
        </div>

        <dl className="grid gap-4 text-sm sm:grid-cols-2">
          <div>
            <dt className="text-slate-400">Username</dt>
            <dd className="mt-1 text-slate-200">{user?.username ?? '—'}</dd>
          </div>
          <div>
            <dt className="text-slate-400">Email</dt>
            <dd className="mt-1 flex items-center gap-2 text-slate-200">
              {user?.email ?? '—'}
              {user && (
                <Badge tone={user.emailVerified ? 'success' : 'warning'}>
                  {user.emailVerified ? 'Verified' : 'Unverified'}
                </Badge>
              )}
            </dd>
          </div>
        </dl>

        {save.isError && (
          <p role="alert" className="text-sm text-red-400">
            {describeApiError(save.error)}
          </p>
        )}
        {saved && (
          <p role="status" className="text-sm text-emerald-300">
            Profile saved.
          </p>
        )}
        <Button
          type="submit"
          loading={save.isPending}
          disabled={!firstName.trim() || !lastName.trim()}
        >
          Save profile
        </Button>
      </form>
    </Card>
  );
}
