import api from '@/lib/axios';

/** Envelope every DevForge endpoint returns. */
export interface ApiEnvelope<T = unknown> {
  success: boolean;
  data: T;
  message: string | null;
}

export interface SignupData {
  firstName: string;
  lastName: string;
  username: string;
  email: string;
  password: string;
  organization: string;
}

export interface LoginData {
  usernameOrEmail: string;
  password: string;
}

export interface ResetPasswordData {
  token: string;
  newPassword: string;
}

export interface UserProfile {
  id: string;
  firstName: string;
  lastName: string;
  username: string;
  email: string;
  phone: string | null;
  avatarUrl: string | null;
  organization: string | null;
  roles: string[];
  status: string;
  oauthProvider: string;
  emailVerified: boolean;
  timezone: string | null;
  language: string | null;
  darkMode: boolean;
  createdAt: string;
  updatedAt: string | null;
  lastLogin: string | null;
}

/**
 * What POST /auth/login returns.
 *
 * When the account has MFA enabled the server withholds the access token and
 * returns a short-lived challenge token instead, so a correct password alone
 * never yields API access. The caller must then post to /auth/login/mfa.
 */
export interface LoginResult {
  mfaRequired: boolean;
  accessToken: string | null;
  challengeToken: string | null;
}

export interface MfaVerifyData {
  challengeToken: string;
  code: string;
}

export interface SessionSummary {
  id: string;
  deviceLabel: string | null;
  userAgent: string | null;
  ipAddress: string | null;
  createdAt: string;
  lastUsedAt: string | null;
  expiresAt: string;
  current: boolean;
}

export interface MfaStatus {
  enabled: boolean;
  remainingBackupCodes: number;
}

export interface MfaEnrolmentChallenge {
  secret: string;
  provisioningUri: string;
}

/**
 * A message the development mail provider captured instead of sending.
 * Only available when the backend runs with devforge.mail.provider=log.
 */
export interface DevMailMessage {
  sentAt: string;
  to: string;
  subject: string;
  body: string;
  actionUrl: string;
}

export const devMailApi = {
  list: () => api.get<ApiEnvelope<DevMailMessage[]>>('/dev/mailbox'),
  clear: () => api.delete<ApiEnvelope<void>>('/dev/mailbox'),
};

export const authApi = {
  signup: (data: SignupData) => api.post<ApiEnvelope<UserProfile>>('/auth/signup', data),

  login: (data: LoginData) => api.post<ApiEnvelope<LoginResult>>('/auth/login', data),

  verifyMfa: (data: MfaVerifyData) => api.post<ApiEnvelope<LoginResult>>('/auth/login/mfa', data),

  logout: () => api.post<ApiEnvelope<void>>('/auth/logout'),

  /** Rotates the refresh cookie and returns a new access token. */
  refresh: () => api.post<ApiEnvelope<string>>('/auth/refresh'),

  /** The signed-in user. Takes no id: the server always returns the caller. */
  me: () => api.get<ApiEnvelope<UserProfile>>('/auth/me'),

  forgotPassword: (email: string) =>
    api.post<ApiEnvelope<void>>('/auth/forgot-password', { email }),

  resetPassword: (data: ResetPasswordData) =>
    api.post<ApiEnvelope<void>>('/auth/reset-password', data),

  // Token goes in the query string because that is what the endpoint expects.
  verifyEmail: (token: string) =>
    api.post<ApiEnvelope<void>>(`/auth/verify-email?token=${encodeURIComponent(token)}`),

  resendVerification: (email: string) =>
    api.post<ApiEnvelope<void>>(`/auth/resend-verification?email=${encodeURIComponent(email)}`),

  listSessions: () => api.get<ApiEnvelope<SessionSummary[]>>('/auth/sessions'),

  revokeSession: (sessionId: string) =>
    api.delete<ApiEnvelope<void>>(`/auth/sessions/${sessionId}`),

  revokeOtherSessions: () =>
    api.post<ApiEnvelope<{ revoked: number }>>('/auth/sessions/revoke-others'),

  mfaStatus: () => api.get<ApiEnvelope<MfaStatus>>('/auth/mfa/status'),

  mfaEnrol: () => api.post<ApiEnvelope<MfaEnrolmentChallenge>>('/auth/mfa/enrol'),

  /** Confirms enrolment. The recovery codes come back once and are never retrievable again. */
  mfaConfirm: (code: string) => api.post<ApiEnvelope<string[]>>('/auth/mfa/confirm', { code }),

  mfaDisable: (code: string) => api.post<ApiEnvelope<void>>('/auth/mfa/disable', { code }),

  mfaRegenerateBackupCodes: (code: string) =>
    api.post<ApiEnvelope<string[]>>('/auth/mfa/backup-codes', { code }),
};
