import { Navigate, Route, Routes } from 'react-router-dom';

import { RedirectIfAuthenticated, RequireAuth } from './components/auth/RequireAuth';
import { AppLayout } from './components/layout/AppLayout';
import { AuthLayout } from './components/layout/AuthLayout';
import { ErrorBoundary } from './components/ui/ErrorBoundary';
import { DashboardPage } from './pages/DashboardPage';
import { DevMailboxPage } from './pages/DevMailboxPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { OrganizationDetailPage } from './pages/OrganizationDetailPage';
import { OrganizationsPage } from './pages/OrganizationsPage';
import { SettingsPage } from './pages/SettingsPage';
import { ForgotPasswordPage } from './pages/auth/ForgotPasswordPage';
import { LoginPage } from './pages/auth/LoginPage';
import { ResetPasswordPage } from './pages/auth/ResetPasswordPage';
import { SignupPage } from './pages/auth/SignupPage';
import { VerifyEmailPage } from './pages/auth/VerifyEmailPage';

function App() {
  return (
    <ErrorBoundary>
      <Routes>
        {/*
          Public routes. Verify-email and reset-password stay reachable while
          signed in: both are reached from an emailed link, and bouncing a
          logged-in user away from them would strand the token.
        */}
        <Route element={<AuthLayout />}>
          <Route path="/verify-email" element={<VerifyEmailPage />} />
          <Route path="/reset-password" element={<ResetPasswordPage />} />

          <Route element={<RedirectIfAuthenticated />}>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/signup" element={<SignupPage />} />
            <Route path="/forgot-password" element={<ForgotPasswordPage />} />
          </Route>
        </Route>

        {/* Everything below requires a session. */}
        <Route element={<RequireAuth />}>
          <Route path="/" element={<AppLayout />}>
            <Route index element={<DashboardPage />} />
            <Route path="organizations" element={<OrganizationsPage />} />
            <Route path="organizations/:organizationId" element={<OrganizationDetailPage />} />
            <Route path="settings" element={<SettingsPage />} />
          </Route>
        </Route>

        {/*
          Public on purpose: it exists to finish a signup before there is an
          account to sign in with. The backend only serves the underlying
          endpoint when the development mail provider is active.
        */}
        <Route path="/dev/mailbox" element={<DevMailboxPage />} />

        <Route path="/404" element={<NotFoundPage />} />
        <Route path="*" element={<Navigate replace to="/404" />} />
      </Routes>
    </ErrorBoundary>
  );
}

export default App;
