import { create } from 'zustand';

import type { UserProfile } from '@/api/auth.api';

interface AuthState {
  user: UserProfile | null;
  accessToken: string | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  sessionExpiresAt: number | null;

  setAuth: (user: UserProfile, accessToken: string, expiresIn?: number) => void;
  clearAuth: () => void;
  updateUser: (updates: Partial<UserProfile>) => void;
  setToken: (token: string) => void;
  setLoading: (loading: boolean) => void;
  initializeAuth: () => void;
}

export const useAuthStore = create<AuthState>((set, get) => ({
  user: null,
  accessToken: null,
  isAuthenticated: false,
  isLoading: true,
  sessionExpiresAt: null,

  setAuth: (user, accessToken, expiresIn) => {
    localStorage.setItem('access_token', accessToken);
    localStorage.setItem('user', JSON.stringify(user));
    const expiresAt = expiresIn ? Date.now() + expiresIn * 1000 : null;
    set({
      user,
      accessToken,
      isAuthenticated: true,
      isLoading: false,
      sessionExpiresAt: expiresAt,
    });
  },

  clearAuth: () => {
    localStorage.removeItem('access_token');
    localStorage.removeItem('user');
    set({
      user: null,
      accessToken: null,
      isAuthenticated: false,
      isLoading: false,
      sessionExpiresAt: null,
    });
  },

  updateUser: (updates) => {
    const currentUser = get().user;
    if (currentUser) {
      const updatedUser = { ...currentUser, ...updates };
      localStorage.setItem('user', JSON.stringify(updatedUser));
      set({ user: updatedUser });
    }
  },

  setToken: (token) => {
    localStorage.setItem('access_token', token);
    set({ accessToken: token });
  },

  setLoading: (loading) => set({ isLoading: loading }),

  initializeAuth: () => {
    const token = localStorage.getItem('access_token');
    const userJson = localStorage.getItem('user');
    if (token && userJson) {
      try {
        const user = JSON.parse(userJson) as UserProfile;
        set({
          user,
          accessToken: token,
          isAuthenticated: true,
          isLoading: false,
        });
      } catch {
        set({ isLoading: false });
      }
    } else {
      set({ isLoading: false });
    }
  },
}));
