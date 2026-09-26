import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Copy, KeyRound, ShieldCheck, ShieldOff } from 'lucide-react';
import QRCode from 'qrcode';
import { useEffect, useState, type FormEvent } from 'react';

import { authApi } from '@/api/auth.api';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { Card, CardHeader } from '@/components/ui/Card';
import { Input } from '@/components/ui/Input';
import { ErrorState, Skeleton } from '@/components/ui/states';
import { describeApiError } from '@/lib/errors';

type Stage = 'idle' | 'enrolling' | 'showing-codes';

/**
 * TOTP enrolment and management.
 *
 * Enrolment is two-step because the server makes it so: /enrol stores a secret
 * but leaves MFA off, and /confirm activates it only after the user proves
 * their authenticator produces a valid code. Skipping the proof would lock out
 * anyone whose QR scan failed.
 */
export function MfaSection() {
  const queryClient = useQueryClient();
  const [stage, setStage] = useState<Stage>('idle');
  const [secret, setSecret] = useState<string | null>(null);
  const [qrDataUrl, setQrDataUrl] = useState<string | null>(null);
  const [code, setCode] = useState('');
  const [backupCodes, setBackupCodes] = useState<string[]>([]);
  const [disableCode, setDisableCode] = useState('');
  const [error, setError] = useState<string | null>(null);

  const status = useQuery({
    queryKey: ['mfa-status'],
    queryFn: async () => (await authApi.mfaStatus()).data.data,
  });

  const enrol = useMutation({
    mutationFn: async () => (await authApi.mfaEnrol()).data.data,
    onSuccess: (challenge) => {
      setSecret(challenge.secret);
      setStage('enrolling');
      setError(null);
      // Rendered client-side: the provisioning URI contains the shared secret,
      // so sending it to a QR image service would hand the second factor to a
      // third party.
      QRCode.toDataURL(challenge.provisioningUri, { width: 220, margin: 1 })
        .then(setQrDataUrl)
        .catch(() => setQrDataUrl(null));
    },
    onError: (err) => setError(describeApiError(err)),
  });

  const confirm = useMutation({
    mutationFn: async (value: string) => (await authApi.mfaConfirm(value)).data.data,
    onSuccess: (codes) => {
      setBackupCodes(codes);
      setStage('showing-codes');
      setCode('');
      setSecret(null);
      setQrDataUrl(null);
      setError(null);
      queryClient.invalidateQueries({ queryKey: ['mfa-status'] });
    },
    onError: (err) => setError(describeApiError(err)),
  });

  const disable = useMutation({
    mutationFn: async (value: string) => (await authApi.mfaDisable(value)).data.data,
    onSuccess: () => {
      setDisableCode('');
      setError(null);
      queryClient.invalidateQueries({ queryKey: ['mfa-status'] });
    },
    onError: (err) => setError(describeApiError(err)),
  });

  const regenerate = useMutation({
    mutationFn: async (value: string) => (await authApi.mfaRegenerateBackupCodes(value)).data.data,
    onSuccess: (codes) => {
      setBackupCodes(codes);
      setStage('showing-codes');
      setDisableCode('');
      setError(null);
      queryClient.invalidateQueries({ queryKey: ['mfa-status'] });
    },
    onError: (err) => setError(describeApiError(err)),
  });

  // Leaving the recovery codes on screen indefinitely is a shoulder-surfing
  // risk; clear them when the user navigates away from this section.
  useEffect(() => () => setBackupCodes([]), []);

  if (status.isLoading)
    return (
      <Card>
        <Skeleton className="h-40" />
      </Card>
    );
  if (status.isError) {
    return (
      <Card>
        <ErrorState message={describeApiError(status.error)} onRetry={() => status.refetch()} />
      </Card>
    );
  }

  const enabled = status.data?.enabled ?? false;

  if (stage === 'showing-codes') {
    return (
      <Card>
        <CardHeader
          title="Save your recovery codes"
          description="Each code works once. They are the only way back in if you lose your authenticator — they are not shown again."
        />
        <ul className="grid grid-cols-2 gap-2 font-mono text-sm text-slate-200 sm:grid-cols-3">
          {backupCodes.map((backupCode) => (
            <li
              key={backupCode}
              className="rounded-lg border border-slate-800 bg-slate-950 px-3 py-2"
            >
              {backupCode}
            </li>
          ))}
        </ul>
        <div className="mt-5 flex gap-3">
          <Button
            variant="secondary"
            leftIcon={<Copy className="h-4 w-4" />}
            onClick={() => navigator.clipboard?.writeText(backupCodes.join('\n'))}
          >
            Copy all
          </Button>
          <Button
            onClick={() => {
              setBackupCodes([]);
              setStage('idle');
            }}
          >
            I have saved them
          </Button>
        </div>
      </Card>
    );
  }

  if (stage === 'enrolling' && secret) {
    return (
      <Card>
        <CardHeader
          title="Set up two-factor authentication"
          description="Scan this with your authenticator app, then enter the code it shows to finish."
        />

        {error && (
          <div
            role="alert"
            className="mb-4 rounded-xl border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-300"
          >
            {error}
          </div>
        )}

        <div className="flex flex-col gap-6 sm:flex-row sm:items-start">
          {qrDataUrl && (
            <img
              src={qrDataUrl}
              alt="QR code for enrolling this account in your authenticator app"
              className="h-[220px] w-[220px] shrink-0 rounded-xl bg-white p-2"
            />
          )}

          <div className="flex-1 space-y-4">
            <div>
              <p className="text-sm text-slate-400">Cannot scan? Enter this key manually:</p>
              <code className="mt-1 block break-all rounded-lg border border-slate-800 bg-slate-950 px-3 py-2 font-mono text-sm text-slate-200">
                {secret}
              </code>
            </div>

            <form
              onSubmit={(event: FormEvent) => {
                event.preventDefault();
                confirm.mutate(code);
              }}
              className="space-y-3"
            >
              <Input
                label="Code from your app"
                value={code}
                onChange={(e) => setCode(e.target.value)}
                inputMode="numeric"
                autoComplete="one-time-code"
                placeholder="123456"
                required
              />
              <div className="flex gap-3">
                <Button type="submit" loading={confirm.isPending}>
                  Confirm and enable
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  onClick={() => {
                    setStage('idle');
                    setSecret(null);
                    setQrDataUrl(null);
                    setError(null);
                  }}
                >
                  Cancel
                </Button>
              </div>
            </form>
          </div>
        </div>
      </Card>
    );
  }

  return (
    <Card>
      <CardHeader
        title="Two-factor authentication"
        description="A second factor means a stolen password is not enough to sign in."
        action={
          <Badge tone={enabled ? 'success' : 'warning'}>
            {enabled ? 'Enabled' : 'Not enabled'}
          </Badge>
        }
      />

      {error && (
        <div
          role="alert"
          className="mb-4 rounded-xl border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-300"
        >
          {error}
        </div>
      )}

      {enabled ? (
        <div className="space-y-5">
          <p className="flex items-center gap-2 text-sm text-slate-400">
            <ShieldCheck className="h-4 w-4 text-emerald-400" aria-hidden="true" />
            {status.data?.remainingBackupCodes ?? 0} recovery code
            {status.data?.remainingBackupCodes === 1 ? '' : 's'} remaining
          </p>

          {/* Both actions require a current code. A live session alone must not
              be able to weaken or reset the second factor, or a stolen access
              token would defeat the point of having one. */}
          <form
            onSubmit={(event: FormEvent) => event.preventDefault()}
            className="max-w-sm space-y-3"
          >
            <Input
              label="Current authentication code"
              value={disableCode}
              onChange={(e) => setDisableCode(e.target.value)}
              inputMode="numeric"
              autoComplete="one-time-code"
              hint="Required to change either setting below."
              placeholder="123456"
            />
            <div className="flex flex-wrap gap-3">
              <Button
                type="button"
                variant="secondary"
                leftIcon={<KeyRound className="h-4 w-4" />}
                loading={regenerate.isPending}
                disabled={!disableCode}
                onClick={() => regenerate.mutate(disableCode)}
              >
                New recovery codes
              </Button>
              <Button
                type="button"
                variant="danger"
                leftIcon={<ShieldOff className="h-4 w-4" />}
                loading={disable.isPending}
                disabled={!disableCode}
                onClick={() => disable.mutate(disableCode)}
              >
                Disable
              </Button>
            </div>
          </form>
        </div>
      ) : (
        <Button
          leftIcon={<ShieldCheck className="h-4 w-4" />}
          loading={enrol.isPending}
          onClick={() => enrol.mutate()}
        >
          Enable two-factor authentication
        </Button>
      )}
    </Card>
  );
}
