import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';

import { authApi } from '@/api/auth.api';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { describeApiError } from '@/lib/errors';
import { passwordStrength } from '@/lib/password';

export function ResetPasswordPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const token = params.get('token') ?? '';

  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const strength = passwordStrength(password);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);

    if (password !== confirm) {
      setError('The two passwords do not match.');
      return;
    }
    if (!strength.acceptable) {
      setError('Choose a stronger password.');
      return;
    }

    setSubmitting(true);
    try {
      await authApi.resetPassword({ token, newPassword: password });
      navigate('/login', { replace: true });
    } catch (err) {
      setError(describeApiError(err));
    } finally {
      setSubmitting(false);
    }
  }

  if (!token) {
    return (
      <div className="space-y-5 text-center">
        <h1 className="text-xl font-semibold text-white">Invalid reset link</h1>
        <p className="text-sm text-slate-400">
          This page needs a token from a password reset email.
        </p>
        <Link to="/forgot-password">
          <Button variant="secondary" fullWidth>
            Request a new link
          </Button>
        </Link>
      </div>
    );
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-5" noValidate>
      <div className="mb-6 text-center">
        <h1 className="text-xl font-semibold text-white">Choose a new password</h1>
      </div>

      {error && (
        <div
          role="alert"
          className="rounded-xl border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-300"
        >
          {error}
        </div>
      )}

      <Input
        label="New password"
        type="password"
        value={password}
        onChange={(e) => setPassword(e.target.value)}
        autoComplete="new-password"
        hint={password ? strength.label : undefined}
        required
      />
      <Input
        label="Confirm new password"
        type="password"
        value={confirm}
        onChange={(e) => setConfirm(e.target.value)}
        autoComplete="new-password"
        required
      />

      <Button type="submit" loading={submitting} fullWidth size="lg">
        Reset password
      </Button>
    </form>
  );
}
