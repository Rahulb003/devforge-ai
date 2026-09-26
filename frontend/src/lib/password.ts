export interface PasswordStrength {
  /** 0–4. Used to fill the meter segments. */
  score: number;
  label: string;
  suggestion: string | null;
  /** Whether the form will allow submission. */
  acceptable: boolean;
  barClass: string;
}

/**
 * Client-side password strength hint.
 *
 * This is guidance for the person typing, not a security control: it runs in
 * the browser and can be bypassed trivially. The server is what actually
 * enforces password policy, and must continue to do so independently.
 *
 * Length is weighted above character classes on purpose — a long passphrase
 * beats a short string with a symbol bolted on, even though the latter looks
 * more "complex" to a naive rule.
 */
export function passwordStrength(password: string): PasswordStrength {
  if (!password) {
    return { score: 0, label: '', suggestion: null, acceptable: false, barClass: 'bg-slate-800' };
  }

  let score = 0;
  if (password.length >= 8) score++;
  if (password.length >= 12) score++;
  if (/[a-z]/.test(password) && /[A-Z]/.test(password)) score++;
  if (/\d/.test(password) && /[^A-Za-z0-9]/.test(password)) score++;

  // A long passphrase should not be penalised for lacking punctuation.
  if (password.length >= 16) score = Math.max(score, 3);

  let suggestion: string | null = null;
  if (password.length < 12) {
    suggestion = 'longer is stronger';
  } else if (!/[A-Z]/.test(password) || !/[a-z]/.test(password)) {
    suggestion = 'mix upper and lower case';
  } else if (!/\d/.test(password) && !/[^A-Za-z0-9]/.test(password)) {
    suggestion = 'add a number or symbol';
  }

  const scale: Array<{ label: string; barClass: string }> = [
    { label: 'Very weak', barClass: 'bg-red-500' },
    { label: 'Weak', barClass: 'bg-red-500' },
    { label: 'Fair', barClass: 'bg-amber-500' },
    { label: 'Good', barClass: 'bg-emerald-500' },
    { label: 'Strong', barClass: 'bg-emerald-400' },
  ];

  const bounded = Math.min(score, 4);
  return {
    score: bounded,
    label: scale[bounded].label,
    suggestion,
    // Minimum 8 characters and at least "Fair": weaker than that is almost
    // always a password the user will regret.
    acceptable: password.length >= 8 && bounded >= 2,
    barClass: scale[bounded].barClass,
  };
}
