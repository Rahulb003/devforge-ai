import { QueryClientProvider } from '@tanstack/react-query';
import { Navigate, Route, Routes } from 'react-router-dom';

import { RedirectIfAuthenticated, RequireAuth } from './components/auth/RequireAuth';
import { queryClient } from './lib/query-client';
import { AppLayout } from './components/layout/AppLayout';
import { AuthLayout } from './components/layout/AuthLayout';
import { ErrorBoundary } from './components/ui/ErrorBoundary';
import { DashboardPage } from './pages/DashboardPage';
import { DevMailboxPage } from './pages/DevMailboxPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { NotificationsPage } from './pages/NotificationsPage';
import { OrganizationDetailPage } from './pages/OrganizationDetailPage';
import { OrganizationsPage } from './pages/OrganizationsPage';
import { ProjectBoardPage } from './pages/ProjectBoardPage';
import { RepositoriesPage } from './pages/RepositoriesPage';
import { RepositoryBrowserPage } from './pages/RepositoryBrowserPage';
import { DocsPage } from './pages/DocsPage';
import { ReviewPage } from './pages/ReviewPage';
import { SettingsPage } from './pages/SettingsPage';
import { ForgotPasswordPage } from './pages/auth/ForgotPasswordPage';
import { LoginPage } from './pages/auth/LoginPage';
import { ResetPasswordPage } from './pages/auth/ResetPasswordPage';
import { SignupPage } from './pages/auth/SignupPage';
import { VerifyEmailPage } from './pages/auth/VerifyEmailPage';

/**
 * The application, including the providers it depends on.
 *
 * <p>QueryClientProvider lives here rather than in main.tsx deliberately. It was in neither, so
 * every screen that calls useQuery — the dashboard is the first one after sign-in — threw on mount
 * and was caught by the error boundary. The tests did not catch it because they supplied their own
 * client while rendering App, which is exactly the composition the real entry point lacked.
 * Owning the provider here makes the two identical, so that gap cannot reopen.
 */
function App() {
  return (
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
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
              <Route
                path="organizations/:organizationId/projects/:projectId"
                element={<ProjectBoardPage />}
              />
              <Route
                path="organizations/:organizationId/projects/:projectId/repositories"
                element={<RepositoriesPage />}
              />
              {/*
                The browser keeps the current directory, ref and open file in the
                query string rather than the path, so a link to a file is one
                someone else can open and Back walks up the tree.
              */}
              <Route
                path="organizations/:organizationId/projects/:projectId/repositories/:repositoryId"
                element={<RepositoryBrowserPage />}
              />
              <Route
                path="organizations/:organizationId/projects/:projectId/repositories/:repositoryId/review"
                element={<ReviewPage />}
              />
              <Route
                path="organizations/:organizationId/projects/:projectId/repositories/:repositoryId/docs"
                element={<DocsPage />}
              />
              <Route path="notifications" element={<NotificationsPage />} />
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
      </QueryClientProvider>
    </ErrorBoundary>
  );
}

export default App;
