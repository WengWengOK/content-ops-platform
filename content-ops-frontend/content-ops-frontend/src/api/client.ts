import axios from 'axios'

const orchestratorBase = import.meta.env.VITE_ORCHESTRATOR_URL || '/orchestrator'

export const apiClient = axios.create({
  baseURL: `${orchestratorBase}/api/v1`,
  timeout: 120000,
  headers: { 'Content-Type': 'application/json' },
})

// 请求拦截：携带登录 Token（后端 contentops.security.enabled=true 时必需）
apiClient.interceptors.request.use((config) => {
  const token = localStorage.getItem('contentops_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    // 401：清除登录态并跳转登录页（鉴权开启时的统一处理）
    if (error?.response?.status === 401) {
      localStorage.removeItem('contentops_token')
      localStorage.removeItem('contentops_user')
      if (!window.location.pathname.startsWith('/login')) {
        window.location.href = '/login'
      }
    }
    console.error('[API Error]', error?.response?.status, error?.message)
    return Promise.reject(error)
  }
)
