// 统一的接口错误：保留后端 code 与响应体（例如 activeJobId）
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly body: Record<string, unknown>

  constructor(status: number, code: string, message: string, body: Record<string, unknown>) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.body = body
  }
}

export async function parseResponse<T>(response: Response, fallback: string): Promise<T> {
  if (response.ok) {
    // 204 或空响应体（如删除接口）没有 JSON，直接返回 undefined
    if (response.status === 204) {
      return undefined as T
    }
    const text = await response.text()
    if (!text.trim()) {
      return undefined as T
    }
    try {
      return JSON.parse(text) as T
    } catch {
      return undefined as T
    }
  }
  let body: Record<string, unknown> = {}
  try {
    body = (await response.json()) as Record<string, unknown>
  } catch {
    // 非 JSON 错误响应
  }
  const message = typeof body.message === 'string' ? body.message : fallback
  const code = typeof body.code === 'string' ? body.code : 'ERROR'
  throw new ApiError(response.status, code, message, body)
}
