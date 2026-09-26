import axios from 'axios';
const api = axios.create({
    baseURL: '/api/v1',
    timeout: 15000,
    headers: {
        'Content-Type': 'application/json',
    },
    withCredentials: true,
});
let isRefreshing = false;
let failedQueue = [];
const processQueue = (error) => {
    failedQueue.forEach((prom) => {
        if (error) {
            prom.reject(error);
        }
        else {
            prom.resolve(undefined);
        }
    });
    failedQueue = [];
};
api.interceptors.request.use((config) => {
    const token = localStorage.getItem('access_token');
    if (token && config.headers) {
        config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
}, (error) => Promise.reject(error));
api.interceptors.response.use((response) => response, async (error) => {
    const originalRequest = error.config;
    if (error.response?.status === 401 && !originalRequest._retry) {
        if (isRefreshing) {
            return new Promise((resolve, reject) => {
                failedQueue.push({ resolve, reject });
            }).then(() => api(originalRequest));
        }
        originalRequest._retry = true;
        isRefreshing = true;
        try {
            const { data } = await axios.post('/api/v1/auth/refresh', {}, { withCredentials: true });
            const newToken = data.data;
            localStorage.setItem('access_token', newToken);
            processQueue(null);
            return api(originalRequest);
        }
        catch (refreshError) {
            processQueue(refreshError);
            localStorage.removeItem('access_token');
            localStorage.removeItem('user');
            window.location.href = '/login';
            return Promise.reject(refreshError);
        }
        finally {
            isRefreshing = false;
        }
    }
    return Promise.reject(error);
});
export default api;
