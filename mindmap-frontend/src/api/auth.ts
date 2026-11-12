import { parseResponse } from '../lib/apiError'
import type { AuthResponse, LoginRequest, RegisterRequest, UserProfile } from '../types/auth'

export async function login(body: LoginRequest): Promise<AuthResponse> {
  const response = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  })
  return parseResponse(response, '登录失败')
}

export async function register(body: RegisterRequest): Promise<AuthResponse> {
  const response = await fetch('/api/auth/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  })
  return parseResponse(response, '注册失败')
}

export async function getCurrentUser(): Promise<UserProfile> {
  const token = window.localStorage.getItem('aimindmap.token')
  const response = await fetch('/api/auth/me', {
    headers: token ? { Authorization: `Bearer ${token}` } : undefined
  })
  return parseResponse(response, '获取当前用户失败')
}
