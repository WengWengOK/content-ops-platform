import axios from 'axios'

const orchestratorBase = import.meta.env.VITE_ORCHESTRATOR_URL || '/orchestrator'

export const apiClient = axios.create({
  baseURL: `${orchestratorBase}/api/v1`,
  timeout: 120000,
  headers: { 'Content-Type': 'application/json' },
})

apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    console.error('[API Error]', error?.response?.status, error?.message)
    return Promise.reject(error)
  }
)
