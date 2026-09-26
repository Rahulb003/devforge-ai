import { CheckCircle2, XCircle } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';

import { authApi } from '@/api/auth.api';
import { Button } from '@/components/ui/Button';
import { LoadingState } from '@/components/ui/states';
import { describeApiError } from '@/lib/errors';

type Status = 'verifying' | 'verified' | 'failed' | 'missing';

/** Consumes the single-use token from the verification link. */
export function VerifyEmailPage() {
  const [params] = useSearchParams();
  const token = params.get('token');
  const [status, setStatus] = useState<Status>(token ? 'verifying' : 'missing');
  const [error, setError] = useState<string | null>(null);

  // The token is single-use, so the request must fire exactly once. React 18
  // StrictMode double-invokes effects in development, which would consume the
  // token on the first call and then report a failure from the second.
  const attempted = useRef(false);

  useEffect(() => {
    if (!token || attempted.current) return;
    attempted.current = true;

    authApi
      .verifyEmail(token)
      .then(() => setStatus('verified'))
      .catch((err) => {
        setError(describeApiError(err));
        setStatus('failed');
      });
  }, [token]);

  if (status === 'verifying') return <LoadingState label="Verifying your email…" />;

  if (status === 'verified') {
    return (
      <div className="space-y-5 text-center">
        <div className="mx-auto grid h-14 w-14 place-items-center rounded-full bg-emerald-500/10 text-emerald-400">
          <CheckCircle2 className="h-7 w-7" aria-hidden="true" />
        </div>
        <h1 className="text-xl font-semibold text-white">Email verified</h1>
        <p className="text-sm text-slate-400">Your account is active. You can sign in now.</p>
        <Link to="/login">
          <Button fullWidth>Continue to sign in</Button>
        </Link>
      </div>
    );
  }

  return (
    <div className="space-y-5 text-center">
      <div className="mx-auto grid h-14 w-14 place-items-center rounded-full bg-red-500/10 text-red-400">
        <XCircle className="h-7 w-7" aria-hidden="true" />
      </div>
      <h1 className="text-xl font-semibold text-white">
        {status === 'missing' ? 'No verification token' : 'Verification failed'}
      </h1>
      <p className="text-sm text-slate-400">
        {status === 'missing'
          ? 'This page needs a token from a verification email.'
          : (error ?? 'The link may have expired or already been used.')}
      </p>
      <Link to="/login">
        <Button variant="secondary" fullWidth>
          Back to sign in
        </Button>
      </Link>
    </div>
  );
}
