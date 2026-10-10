import { Sparkles } from 'lucide-react';
import { useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';

import { authApi } from '@/api/auth.api';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { describeApiError } from '@/lib/errors';
import { useAuthStore } from '@/stores/authStore';

/**
 * Password login, with the MFA hand-off.
 *
 * The server may answer a correct password with `mfaRequired` and a challenge
 * token instead of an access token. That is not an error state — it is the
 * second half of a successful login, so the form swaps to a code field rather
 * than navigating away.
 */
export function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const setAuth = useAuthStore((s) => s.setAuth);

  const [usernameOrEmail, setUsernameOrEmail] = useState('');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [challengeToken, setChallengeToken] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  // Where the user was heading before being bounced to login.
  const from = (location.state as { from?: string } | null)?.from ?? '/';
  const accountDeleted = (location.state as { accountDeleted?: boolean } | null)?.accountDeleted;

  async function completeLogin() {
    // The session is an HttpOnly cookie the page cannot see, so the profile is fetched to render a
    // name and roles; that call is also the proof the cookie took.
    const profile = await authApi.me();
    setAuth(profile.data.data);
    navigate(from, { replace: true });
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);

    try {
      if (challengeToken) {
        await authApi.verifyMfa({ challengeToken, code });
        await completeLogin();
        return;
      }

      const { data } = await authApi.login({ usernameOrEmail, password });

      if (data.data.mfaRequired && data.data.challengeToken) {
        setChallengeToken(data.data.challengeToken);
        setCode('');
        return;
      }

      await completeLogin();
    } catch (err) {
      setError(describeApiError(err));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-5" noValidate>
      <div className="mb-8 text-center">
        <div className="mb-4 inline-flex items-center gap-2 text-2xl font-semibold text-white">
          <Sparkles className="h-7 w-7 text-indigo-400" aria-hidden="true" />
          DevForge AI
        </div>
        <h1 className="text-xl font-semibold text-white">
          {challengeToken ? 'Two-factor authentication' : 'Sign in'}
        </h1>
        <p className="mt-1 text-sm text-slate-400">
          {challengeToken
            ? 'Enter the 6-digit code from your authenticator app, or a recovery code.'
            : 'Build. Review. Test. Deploy. Collaborate.'}
        </p>
      </div>

      {accountDeleted && !error && (
        <div
          role="status"
          className="rounded-xl border border-slate-700 bg-slate-800 px-4 py-3 text-sm text-slate-200"
        >
          Your account has been deleted.
        </div>
      )}

      {error && (
        <div
          role="alert"
          className="rounded-xl border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-300"
        >
          {error}
        </div>
      )}

      {challengeToken ? (
        <Input
          label="Authentication code"
          value={code}
          onChange={(e) => setCode(e.target.value)}
          autoComplete="one-time-code"
          inputMode="numeric"
          placeholder="123456"
          required
        />
      ) : (
        <>
          <Input
            label="Username or email"
            value={usernameOrEmail}
            onChange={(e) => setUsernameOrEmail(e.target.value)}
            autoComplete="username"
            required
          />
          <Input
            label="Password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            required
          />
        </>
      )}

      <Button type="submit" loading={submitting} fullWidth size="lg">
        {challengeToken ? 'Verify' : 'Sign in'}
      </Button>

      {challengeToken ? (
        <button
          type="button"
          onClick={() => {
            setChallengeToken(null);
            setError(null);
          }}
          className="w-full text-sm text-slate-400 underline-offset-4 hover:text-slate-200 hover:underline"
        >
          Back to sign in
        </button>
      ) : (
        <div className="flex items-center justify-between text-sm">
          <Link
            to="/forgot-password"
            className="text-slate-400 underline-offset-4 hover:text-slate-200 hover:underline"
          >
            Forgot password?
          </Link>
          <Link
            to="/signup"
            className="font-medium text-indigo-400 underline-offset-4 hover:underline"
          >
            Create an account
          </Link>
        </div>
      )}
    </form>
  );
}
