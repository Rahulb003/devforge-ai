import { describe, expect, it } from 'vitest';

import { passwordStrength } from '@/lib/password';

describe('passwordStrength', () => {
  it('reports nothing for an empty password', () => {
    const result = passwordStrength('');
    expect(result.score).toBe(0);
    expect(result.acceptable).toBe(false);
    expect(result.label).toBe('');
  });

  it('rejects anything shorter than eight characters', () => {
    expect(passwordStrength('Ab1!').acceptable).toBe(false);
    expect(passwordStrength('Ab1!xy').acceptable).toBe(false);
  });

  it('accepts a mixed password of reasonable length', () => {
    const result = passwordStrength('Str0ng-Passw0rd!');
    expect(result.acceptable).toBe(true);
    expect(result.score).toBeGreaterThanOrEqual(3);
  });

  it('does not penalise a long passphrase for lacking punctuation', () => {
    // Length is the dominant factor in practice, so a memorable passphrase
    // should not score below a short string with a symbol bolted on.
    const passphrase = passwordStrength('correct horse battery staple');
    const shortWithSymbol = passwordStrength('Ab1!efg');

    expect(passphrase.acceptable).toBe(true);
    expect(passphrase.score).toBeGreaterThan(shortWithSymbol.score);
  });

  it('suggests more length before anything else', () => {
    expect(passwordStrength('Abcdef1!').suggestion).toBe('longer is stronger');
  });

  it('caps the score so the meter cannot overflow', () => {
    const result = passwordStrength('aVeryLongAndComplexPassphrase123!@#$%^');
    expect(result.score).toBeLessThanOrEqual(4);
  });
});
