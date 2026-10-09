import { CheckCircle2, Sparkles } from 'lucide-react';
import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';

import { authApi } from '@/api/auth.api';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { describeApiError } from '@/lib/errors';
import { passwordStrength } from '@/lib/password';

export function SignupPage() {
  const [form, setForm] = useState({
    firstName: '',
    lastName: '',
    username: '',
    email: '',
    password: '',
    organization: '',
  });
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [registeredEmail, setRegisteredEmail] = useState<string | null>(null);
  const [needsVerification, setNeedsVerification] = useState(true);

  const strength = passwordStrength(form.password);

  function update(field: keyof typeof form) {
    return (event: React.ChangeEvent<HTMLInputElement>) =>
      setForm((f) => ({ ...f, [field]: event.target.value }));
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);

    if (!strength.acceptable) {
      setError('Choose a stronger password.');
      return;
    }

    setSubmitting(true);
    try {
      const { data } = await authApi.signup(form);
      // Whether the account is usable straight away depends on the server's
      // require-email-verification setting, so the confirmation is driven by the
      // status it returns rather than by an assumption baked into the UI.
      setNeedsVerification(!data.data.emailVerified);
      setRegisteredEmail(form.email);
    } catch (err) {
      setError(describeApiError(err));
    } finally {
      setSubmitting(false);
    }
  }

  if (registeredEmail) {
    return (
      <div className="space-y-5 text-center">
        <div className="mx-auto grid h-14 w-14 place-items-center rounded-full bg-emerald-500/10 text-emerald-400">
          <CheckCircle2 className="h-7 w-7" aria-hidden="true" />
        </div>
        <h1 className="text-xl font-semibold text-white">
          {needsVerification ? 'Check your email' : 'Account created'}
        </h1>
        <p className="text-sm text-slate-400">
          {needsVerification ? (
            <>
              We sent a verification link to{' '}
              <span className="text-slate-200">{registeredEmail}</span>. You need to verify before
              you can sign in.
            </>
          ) : (
            <>
              Your account <span className="text-slate-200">{registeredEmail}</span> is ready. You
              can sign in now.
            </>
          )}
        </p>

        {needsVerification && (
          <div className="rounded-xl border border-slate-800 bg-slate-900/60 px-4 py-3 text-left">
            <p className="text-xs text-slate-400">
              Running locally with the development mail provider? Nothing was actually sent — open
              the captured message to continue.
            </p>
            <Link
              to="/dev/mailbox"
              className="mt-2 inline-block text-xs font-medium text-indigo-400 underline-offset-4 hover:underline"
            >
              Open the development mailbox
            </Link>
          </div>
        )}

        <Link to="/login">
          <Button variant={needsVerification ? 'secondary' : 'primary'} fullWidth>
            {needsVerification ? 'Back to sign in' : 'Continue to sign in'}
          </Button>
        </Link>
      </div>
    );
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-4" noValidate>
      <div className="mb-6 text-center">
        <div className="mb-4 inline-flex items-center gap-2 text-2xl font-semibold text-white">
          <Sparkles className="h-7 w-7 text-indigo-400" aria-hidden="true" />
          DevForge AI
        </div>
        <h1 className="text-xl font-semibold text-white">Create your account</h1>
      </div>

      {error && (
        <div
          role="alert"
          className="rounded-xl border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-300"
        >
          {error}
        </div>
      )}

      <div className="grid gap-4 sm:grid-cols-2">
        <Input
          label="First name"
          value={form.firstName}
          onChange={update('firstName')}
          autoComplete="given-name"
          required
        />
        <Input
          label="Last name"
          value={form.lastName}
          onChange={update('lastName')}
          autoComplete="family-name"
          required
        />
      </div>

      <Input
        label="Username"
        value={form.username}
        onChange={update('username')}
        autoComplete="username"
        required
      />
      <Input
        label="Email"
        type="email"
        value={form.email}
        onChange={update('email')}
        autoComplete="email"
        required
      />
      <Input
        label="Organization"
        value={form.organization}
        onChange={update('organization')}
        autoComplete="organization"
      />

      <div>
        <Input
          label="Password"
          type="password"
          value={form.password}
          onChange={update('password')}
          autoComplete="new-password"
          required
        />
        {form.password && (
          <div className="mt-2 space-y-1.5">
            <div className="flex gap-1" aria-hidden="true">
              {[0, 1, 2, 3].map((i) => (
                <div
                  key={i}
                  className={`h-1 flex-1 rounded-full ${i < strength.score ? strength.barClass : 'bg-slate-800'}`}
                />
              ))}
            </div>
            {/* Announced politely so a screen reader hears the rating change
                without interrupting typing. */}
            <p className="text-xs text-slate-400" aria-live="polite">
              {strength.label}
              {strength.suggestion && (
                <span className="text-slate-400"> — {strength.suggestion}</span>
              )}
            </p>
          </div>
        )}
      </div>

      <Button type="submit" loading={submitting} fullWidth size="lg">
        Create account
      </Button>

      <p className="text-center text-sm text-slate-400">
        Already have an account?{' '}
        <Link
          to="/login"
          className="font-medium text-indigo-400 underline-offset-4 hover:underline"
        >
          Sign in
        </Link>
      </p>
    </form>
  );
}
