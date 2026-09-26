import type { AuthResponse, UserProfile } from './auth.api';

import api from '@/lib/axios';

export interface UpdateProfileData {
  firstName?: string;
  lastName?: string;
  phone?: string;
  avatarUrl?: string;
  organization?: string;
  timezone?: string;
  language?: string;
  darkMode?: boolean;
}

export interface ChangePasswordData {
  currentPassword: string;
  newPassword: string;
}

export const userApi = {
  getProfile: () => api.get<AuthResponse<UserProfile>>('/users/me'),

  updateProfile: (data: UpdateProfileData) => api.put<AuthResponse<UserProfile>>('/users/me', data),

  changePassword: (data: ChangePasswordData) =>
    api.put<AuthResponse<void>>('/users/change-password', data),

  deleteAccount: () => api.delete<AuthResponse<void>>('/users/me'),

  getAdminUsers: (page = 0, size = 20) =>
    api.get<AuthResponse<{ content: UserProfile[]; totalPages: number; totalElements: number }>>(
      `/admin/users?page=${page}&size=${size}`,
    ),
};
