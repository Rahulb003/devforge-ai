import api from '@/lib/axios';
export const authApi = {
    signup: (data) => api.post('/auth/signup', data),
    login: (data) => api.post('/auth/login', data),
    logout: () => api.post('/auth/logout'),
    refresh: () => api.post('/auth/refresh'),
    forgotPassword: (email) => api.post('/auth/forgot-password', { email }),
    resetPassword: (data) => api.post('/auth/reset-password', data),
    verifyEmail: (token) => api.post(`/auth/verify-email?token=${token}`),
    resendVerification: (email) => api.post(`/auth/resend-verification?email=${email}`),
};
