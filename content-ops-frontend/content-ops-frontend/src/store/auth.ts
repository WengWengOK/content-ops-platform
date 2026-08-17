/**
 * 认证状态（Zustand）— Token 与用户信息持久化到 localStorage。
 */
import { create } from 'zustand'
import { login as apiLogin, register as apiRegister } from '@/api/auth'
import type { LoginRequest, RegisterRequest } from '@/api/auth'

export interface AuthUser {
  userId: string
  username: string
}

interface AuthState {
  token: string | null
  user: AuthUser | null
  login: (req: LoginRequest) => Promise<void>
  register: (req: RegisterRequest) => Promise<void>
  logout: () => void
}

export const TOKEN_KEY = 'contentops_token'
export const USER_KEY = 'contentops_user'

function loadUser(): AuthUser | null {
  try {
    const raw = localStorage.getItem(USER_KEY)
    return raw ? (JSON.parse(raw) as AuthUser) : null
  } catch {
    return null
  }
}

export const useAuth = create<AuthState>((set) => ({
  token: localStorage.getItem(TOKEN_KEY),
  user: loadUser(),

  login: async (req) => {
    const data = await apiLogin(req)
    localStorage.setItem(TOKEN_KEY, data.token)
    const user: AuthUser = { userId: data.userId, username: data.username }
    localStorage.setItem(USER_KEY, JSON.stringify(user))
    set({ token: data.token, user })
  },

  register: async (req) => {
    await apiRegister(req)
  },

  logout: () => {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
    set({ token: null, user: null })
  },
}))
