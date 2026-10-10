import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';

import { authApi } from '@/api/auth.api';
import { Button } from '@/components/ui/Button';
import { Card, CardHeader } from '@/components/ui/Card';
import { Input } from '@/components/ui/Input';
import { describeApiError } from '@/lib/errors';
import { useAuthStore } from '@/stores/authStore';

/**
 * Deleting your own account.
 *
 * Behind a button first, so the form is not one stray Enter away on the settings page. The server
 * checks everything that matters - the typed username, the password, a second factor, and that no
 * organization would be left without an owner; the button here only stays disabled until the
 * username matches, to save a pointless request.
 */
export function DeleteAccountSection() {
  const user = useAuthStore((state) => state.user);
  const clearAuth = useAuthStore((state) => state.clearAuth);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [mfaCode, setMfaCode] = useState('');

  const mfa = useQuery({
    queryKey: ['mfa-status'],
    queryFn: async () => (await authApi.mfaStatus()).data.data,
    enabled: open,
  });

  const remove = useMutation({
    mutationFn: () =>
      authApi.deleteAccount({ username, password, mfaCode: mfaCode.trim() || undefined }),
    onSuccess: () => {
      queryClient.clear();
      clearAuth();
      navigate('/login', { replace: true, state: { accountDeleted: true } });
    },
  });

  const matches = !!user && username.trim().toLowerCase() === user.username.toLowerCase();

  function submit(event: FormEvent) {
    event.preventDefault();
    if (matches && password) remove.mutate();
  }

  return (
    <Card>
      <CardHeader
        title="Delete account"
        description="Erases your name, email address and sign-in history, ends every session and removes you from every organization. It cannot be undone."
      />
      {!open ? (
        <Button variant="danger" onClick={() => setOpen(true)}>
          Delete my account
        </Button>
      ) : (
        <form onSubmit={submit} aria-label="Delete account" className="space-y-4">
          <p className="text-sm text-slate-300">
            If you are the only owner of an organization, make someone else an owner or delete the
            organization first. Work you contributed - tasks, commits, comments - stays in the
            projects, attributed to a deleted user.
          </p>
          <div className="grid gap-4 sm:grid-cols-3">
            <Input
              label={`Type your username (${user?.username ?? ''})`}
              autoComplete="off"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
            />
            <Input
              label="Password"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
            {mfa.data?.enabled && (
              <Input
                label="Authentication code"
                inputMode="numeric"
                autoComplete="one-time-code"
                value={mfaCode}
                onChange={(e) => setMfaCode(e.target.value)}
              />
            )}
          </div>
          {remove.isError && (
            <p role="alert" className="text-sm text-red-400">
              {describeApiError(remove.error)}
            </p>
          )}
          <div className="flex gap-3">
            <Button
              type="submit"
              variant="danger"
              loading={remove.isPending}
              disabled={!matches || !password}
            >
              Delete account permanently
            </Button>
            <Button type="button" variant="ghost" onClick={() => setOpen(false)}>
              Cancel
            </Button>
          </div>
        </form>
      )}
    </Card>
  );
}
