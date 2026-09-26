import { MailCheck } from 'lucide-react';
import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';

import { authApi } from '@/api/auth.api';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { describeApiError } from '@/lib/errors';

export function ForgotPasswordPage() {
  const [email, setEmail] = useState('');
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await authApi.forgotPassword(email);
      setSent(true);
    } catch (err) {
      setError(describeApiError(err));
    } finally {
      setSubmitting(false);
    }
  }

  if (sent) {
    return (
      <div className="space-y-5 text-center">
        <div className="mx-auto grid h-14 w-14 place-items-center rounded-full bg-indigo-500/10 text-indigo-400">
          <MailCheck className="h-7 w-7" aria-hidden="true" />
        </div>
        <h1 className="text-xl font-semibold text-white">Check your email</h1>
        {/*
          Worded so it reveals nothing either way. The server answers a known
          and an unknown address identically to avoid being an account
          enumeration oracle; the UI must not undo that by confirming the
          account exists.
        */}
        <p className="text-sm text-slate-400">
          If an account exists for <span className="text-slate-200">{email}</span>, a password reset
          link is on its way.
        </p>
        <Link to="/login">
          <Button variant="secondary" fullWidth>
            Back to sign in
          </Button>
        </Link>
      </div>
    );
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-5" noValidate>
      <div className="mb-6 text-center">
        <h1 className="text-xl font-semibold text-white">Reset your password</h1>
        <p className="mt-1 text-sm text-slate-400">We will email you a link to set a new one.</p>
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
        label="Email"
        type="email"
        value={email}
        onChange={(e) => setEmail(e.target.value)}
        autoComplete="email"
        required
      />

      <Button type="submit" loading={submitting} fullWidth size="lg">
        Send reset link
      </Button>

      <p className="text-center text-sm">
        <Link
          to="/login"
          className="text-slate-400 underline-offset-4 hover:text-slate-200 hover:underline"
        >
          Back to sign in
        </Link>
      </p>
    </form>
  );
}
