import { create } from 'zustand'
import { getCurrentUser, login, register } from '../api/auth'
import { clearToken, DOCUMENT_ID_KEY, getToken, setToken } from '../lib/http'
import type {
  AuthResponse,
  LoginRequest,
  RegisterRequest,
  UserProfile
} from '../types/auth'
import { ApiError } from '../lib/apiError'

interface AuthState {
  token: string | null
  user: UserProfile | null
  initialized: boolean
  submitting: boolean
  error: string
  bootstrap: () => Promise<void>
  login: (body: LoginRequest) => Promise<boolean>
  register: (body: RegisterRequest) => Promise<boolean>
  clearError: () => void
  logout: () => void
}

function resetLocalLoginState() {
  clearToken()
  window.localStorage.removeItem(DOCUMENT_ID_KEY)
}

export const useAuthStore = create<AuthState>((set, get) => ({
  token: getToken(),
  user: null,
  initialized: !getToken(),
  submitting: false,
  error: '',

  bootstrap: async () => {
    if (!get().token) {
      set({ initialized: true, user: null })
      return
    }

    try {
      const user = await getCurrentUser()
      set({ user, initialized: true, error: '' })
    } catch (reason) {
      resetLocalLoginState()
      if (reason instanceof ApiError && reason.status === 401) {
        set({ token: null, user: null, initialized: true, error: '' })
      } else {
        set({
          token: null,
          user: null,
          initialized: true,
          error: reason instanceof Error ? reason.message : '无法连接后端服务'
        })
      }
    }
  },

  login: async (body) => {
    set({ submitting: true, error: '' })
    try {
      const result: AuthResponse = await login(body)
      setToken(result.token)
      set({ token: result.token, user: result.user })
      return true
    } catch (reason) {
      set({ error: reason instanceof Error ? reason.message : '登录失败' })
      return false
    } finally {
      set({ submitting: false })
    }
  },

  register: async (body) => {
    set({ submitting: true, error: '' })
    try {
      const result: AuthResponse = await register(body)
      setToken(result.token)
      set({ token: result.token, user: result.user })
      return true
    } catch (reason) {
      set({ error: reason instanceof Error ? reason.message : '注册失败' })
      return false
    } finally {
      set({ submitting: false })
    }
  },

  clearError: () => set({ error: '' }),

  logout: () => {
    resetLocalLoginState()
    window.location.assign('/')
    window.location.reload()
  }
}))
