/**
 * 认证 API — 注册 / 登录（后端 /api/v1/auth/*）。
 */
import { apiClient } from './client'
import type { AgentResponse } from '@/types'

export interface LoginRequest {
  username: string
  password: string
}

export interface RegisterRequest {
  username: string
  password: string
}

export interface AuthData {
  token: string
  userId: string
  username: string
}

function unwrap<T>(resp: AgentResponse<T>): T {
  if (resp.success) return resp.data
  throw new Error(resp.message || resp.error || '请求失败')
}

export async function login(req: LoginRequest): Promise<AuthData> {
  const { data } = await apiClient.post<AgentResponse<AuthData>>('/auth/login', req)
  return unwrap(data)
}

export async function register(req: RegisterRequest): Promise<{ userId: string; username: string }> {
  const { data } = await apiClient.post<AgentResponse<{ userId: string; username: string }>>(
    '/auth/register',
    req
  )
  return unwrap(data)
}
