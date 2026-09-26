import api from '@/lib/axios';
export const userApi = {
    getProfile: () => api.get('/users/me'),
    updateProfile: (data) => api.put('/users/me', data),
    changePassword: (data) => api.put('/users/change-password', data),
    deleteAccount: () => api.delete('/users/me'),
    getAdminUsers: (page = 0, size = 20) => api.get(`/admin/users?page=${page}&size=${size}`),
};
