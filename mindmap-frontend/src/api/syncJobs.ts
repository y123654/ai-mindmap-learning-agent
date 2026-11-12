import type { CompleteJobResponse, SyncJob } from '../types/mindmap'
import { apiJson, getToken } from '../lib/http'

export async function createSyncJob(
  documentId: string,
  expectedContentVersion: number
): Promise<SyncJob> {
  return apiJson(`/api/documents/${documentId}/sync-jobs`, {
    method: 'POST',
    json: { expectedContentVersion, mode: 'incremental' }
  }, '创建同步任务失败')
}

export async function getSyncJob(jobId: string): Promise<SyncJob> {
  return apiJson(`/api/sync-jobs/${jobId}`, {}, '查询同步任务失败')
}

export async function markAnimationStarted(jobId: string): Promise<SyncJob> {
  return apiJson(`/api/sync-jobs/${jobId}/animation-started`, { method: 'POST' }, '通知动画开始失败')
}

export async function completeSyncJob(jobId: string): Promise<CompleteJobResponse> {
  return apiJson(`/api/sync-jobs/${jobId}/complete`, { method: 'POST' }, '发布思维导图失败')
}

export async function skipSyncAnimation(jobId: string): Promise<CompleteJobResponse> {
  return apiJson(`/api/sync-jobs/${jobId}/skip-animation`, { method: 'POST' }, '跳过动画失败')
}

export async function cancelSyncJob(jobId: string): Promise<SyncJob> {
  return apiJson(`/api/sync-jobs/${jobId}/cancel`, { method: 'POST' }, '取消同步任务失败')
}
// v1.1 §11.2 SSE 进度订阅。修订第 44 条：载荷 id 均为外部字符串。
export interface SyncJobEventHandlers {
  onStatus?: (payload: { jobId: string; status: string }) => void
  onReady?: (job: SyncJob) => void
  onFailed?: (job: SyncJob) => void
  onCancelled?: (payload: { jobId: string; status: string }) => void
  onCompleted?: (payload: { jobId: string; status: string; graphVersion?: number }) => void
  onError?: () => void
}

export function openSyncJobEvents(
  jobId: string,
  handlers: SyncJobEventHandlers
): EventSource {
  const params = new URLSearchParams({ access_token: getToken() ?? '' })
  const source = new EventSource(`/api/sync-jobs/${jobId}/events?${params.toString()}`)

  const listen = (
    name: string,
    fn: ((data: unknown) => void) | undefined
  ): void => {
    if (!fn) return
    source.addEventListener(name, (event) => {
      const messageEvent = event as MessageEvent
      if (!messageEvent.data) return
      try {
        fn(JSON.parse(messageEvent.data))
      } catch {
        // 心跳或异常数据忽略
      }
    })
  }

  listen('status', (data) => handlers.onStatus?.(data as { jobId: string; status: string }))
  listen('ready', (data) => handlers.onReady?.(data as SyncJob))
  listen('failed', (data) => handlers.onFailed?.(data as SyncJob))
  listen('cancelled', (data) =>
    handlers.onCancelled?.(data as { jobId: string; status: string })
  )
  listen('completed', (data) =>
    handlers.onCompleted?.(data as { jobId: string; status: string; graphVersion?: number })
  )
  source.onerror = () => handlers.onError?.()
  return source
}