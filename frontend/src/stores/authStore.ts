import { create } from 'zustand';

import type { UserProfile } from '@/api/auth.api';

interface AuthState {
  user: UserProfile | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  sessionExpiresAt: number | null;

  setAuth: (user: UserProfile, expiresIn?: number) => void;
  clearAuth: () => void;
  updateUser: (updates: Partial<UserProfile>) => void;
  setLoading: (loading: boolean) => void;
  initializeAuth: () => void;
}

export const useAuthStore = create<AuthState>((set, get) => ({
  user: null,
  isAuthenticated: false,
  isLoading: true,
  sessionExpiresAt: null,

  setAuth: (user, expiresIn) => {
    localStorage.setItem('user', JSON.stringify(user));
    const expiresAt = expiresIn ? Date.now() + expiresIn * 1000 : null;
    set({
      user,
      isAuthenticated: true,
      isLoading: false,
      sessionExpiresAt: expiresAt,
    });
  },

  clearAuth: () => {
    localStorage.removeItem('user');
    set({
      user: null,
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

  setLoading: (loading) => set({ isLoading: loading }),

  initializeAuth: () => {
    // Earlier versions kept the access token here, where any script could read it. It now lives
    // only in an HttpOnly cookie; a copy left behind by an old version is removed, not used.
    localStorage.removeItem('access_token');
    // The stored profile is only for rendering the shell. Whether the session is still valid is
    // the server's call: an expired cookie answers 401, and the interceptor refreshes or sends the
    // user to sign in.
    const userJson = localStorage.getItem('user');
    if (userJson) {
      try {
        const user = JSON.parse(userJson) as UserProfile;
        set({
          user,
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
