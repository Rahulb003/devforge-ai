import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';

import { authApi } from '@/api/auth.api';
import { Button } from '@/components/ui/Button';
import { Card, CardHeader } from '@/components/ui/Card';
import { Input } from '@/components/ui/Input';
import { describeApiError } from '@/lib/errors';

/**
 * Changing the password while signed in. The server checks the current password, enforces the
 * signup rules and signs out every other device; the confirmation field is checked here only to
 * catch a typo before it locks the user out of their own account.
 */
export function PasswordSection() {
  const queryClient = useQueryClient();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [signedOut, setSignedOut] = useState<number | null>(null);

  const change = useMutation({
    mutationFn: async () => (await authApi.changePassword(current, next)).data.data,
    onSuccess: (result) => {
      setCurrent('');
      setNext('');
      setConfirm('');
      setSignedOut(result.otherSessionsSignedOut);
      void queryClient.invalidateQueries({ queryKey: ['sessions'] });
    },
  });

  const mismatch = confirm.length > 0 && confirm !== next;

  function submit(event: FormEvent) {
    event.preventDefault();
    setSignedOut(null);
    if (!mismatch) change.mutate();
  }

  return (
    <Card>
      <CardHeader
        title="Password"
        description="At least 12 characters with uppercase, lowercase, a number and a symbol. Changing it signs out your other devices."
      />
      <form onSubmit={submit} aria-label="Change password" className="space-y-4">
        <div className="grid gap-4 sm:grid-cols-3">
          <Input
            label="Current password"
            type="password"
            autoComplete="current-password"
            value={current}
            onChange={(e) => setCurrent(e.target.value)}
          />
          <Input
            label="New password"
            type="password"
            autoComplete="new-password"
            value={next}
            onChange={(e) => setNext(e.target.value)}
          />
          <Input
            label="Confirm new password"
            type="password"
            autoComplete="new-password"
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
            error={mismatch ? 'Does not match the new password' : undefined}
          />
        </div>
        {change.isError && (
          <p role="alert" className="text-sm text-red-400">
            {describeApiError(change.error)}
          </p>
        )}
        {signedOut !== null && (
          <p role="status" className="text-sm text-emerald-300">
            Password changed.{' '}
            {signedOut === 0
              ? 'No other devices were signed in.'
              : `${signedOut} other ${signedOut === 1 ? 'device was' : 'devices were'} signed out.`}
          </p>
        )}
        <Button
          type="submit"
          loading={change.isPending}
          disabled={!current || !next || !confirm || mismatch}
        >
          Change password
        </Button>
      </form>
    </Card>
  );
}
