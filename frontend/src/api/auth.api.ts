import api from '@/lib/axios';

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
  rememberMe: boolean;
}

export interface ResetPasswordData {
  token: string;
  newPassword: string;
}

export interface AuthResponse<T = unknown> {
  success: boolean;
  data: T;
  message: string;
}

export interface UserProfile {
  id: string;
  firstName: string;
  lastName: string;
  username: string;
  email: string;
  phone: string | null;
  avatarUrl: string | null;
  organization: string;
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

export interface LoginResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  user: UserProfile;
}

export const authApi = {
  signup: (data: SignupData) => api.post<AuthResponse<UserProfile>>('/auth/signup', data),

  login: (data: LoginData) => api.post<AuthResponse<LoginResponse>>('/auth/login', data),

  logout: () => api.post<AuthResponse<void>>('/auth/logout'),

  refresh: () => api.post<AuthResponse<string>>('/auth/refresh'),

  forgotPassword: (email: string) =>
    api.post<AuthResponse<void>>('/auth/forgot-password', { email }),

  resetPassword: (data: ResetPasswordData) =>
    api.post<AuthResponse<void>>('/auth/reset-password', data),

  verifyEmail: (token: string) => api.post<AuthResponse<void>>(`/auth/verify-email?token=${token}`),

  resendVerification: (email: string) =>
    api.post<AuthResponse<void>>(`/auth/resend-verification?email=${email}`),
};
