import { parseResponse } from './apiError'

export const TOKEN_KEY = 'aimindmap.token'
export const DOCUMENT_ID_KEY = 'aimindmap.currentDocumentId'

export function getToken(): string | null {
  return window.localStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string): void {
  window.localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken(): void {
  window.localStorage.removeItem(TOKEN_KEY)
}

export interface ApiRequestOptions extends RequestInit {
  json?: unknown
}

function buildHeaders(init: ApiRequestOptions, hasJsonBody: boolean): Headers {
  const headers = new Headers(init.headers)
  const token = getToken()
  if (token && !headers.has('Authorization')) {
    headers.set('Authorization', `Bearer ${token}`)
  }
  if (hasJsonBody && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  return headers
}

function notifyUnauthorized(): void {
  window.dispatchEvent(new CustomEvent('aimindmap:unauthorized'))
}

export async function apiJson<T>(
  path: string,
  options: ApiRequestOptions = {},
  fallback = '请求失败'
): Promise<T> {
  const hasJsonBody = options.json !== undefined
  const response = await fetch(path, {
    ...options,
    headers: buildHeaders(options, hasJsonBody),
    body: hasJsonBody ? JSON.stringify(options.json) : options.body
  })

  const isAuthSubmit = path === '/api/auth/login' || path === '/api/auth/register'
  if (response.status === 401 && !isAuthSubmit) {
    clearToken()
    notifyUnauthorized()
  }

  return parseResponse<T>(response, fallback)
}
