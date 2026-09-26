import { useEffect } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router-dom';

import { LoadingState } from '@/components/ui/states';
import { useAuthStore } from '@/stores/authStore';

/**
 * Gate for the authenticated part of the app.
 *
 * This is a routing convenience, not a security control. Hiding a route in the
 * browser protects nothing — the bundle is public and the API is reachable
 * directly. Authorization is enforced server-side on every request; this only
 * saves the user from staring at a screen that would fail to load.
 *
 * It waits for the store to finish restoring a session before deciding, or a
 * page refresh would bounce a signed-in user to the login screen during the
 * moment before their token is read back.
 */
export function RequireAuth() {
  const { isAuthenticated, isLoading, initializeAuth } = useAuthStore();
  const location = useLocation();

  useEffect(() => {
    initializeAuth();
  }, [initializeAuth]);

  if (isLoading) {
    return (
      <div className="grid min-h-screen place-items-center bg-slate-950">
        <LoadingState label="Restoring your session…" />
      </div>
    );
  }

  if (!isAuthenticated) {
    // Remember the destination so login can return the user to it.
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }

  return <Outlet />;
}

/**
 * Inverse gate: keeps a signed-in user off the login and signup screens.
 */
export function RedirectIfAuthenticated() {
  const { isAuthenticated, isLoading, initializeAuth } = useAuthStore();

  useEffect(() => {
    initializeAuth();
  }, [initializeAuth]);

  if (isLoading) {
    return (
      <div className="grid min-h-screen place-items-center bg-slate-950">
        <LoadingState label="Loading…" />
      </div>
    );
  }

  return isAuthenticated ? <Navigate to="/" replace /> : <Outlet />;
}
