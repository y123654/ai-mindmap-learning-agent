import { create } from 'zustand'
import type {
  CompleteJobResponse,
  MindMapGraph,
  MindMapNode,
  NodeActionResponse,
  MindMapResponse,
  SyncJob,
  SyncJobStatus,
  ValidatedOperation
} from '../types/mindmap'
import { LOCKED_STATUSES } from '../types/mindmap'
import {
  addNode,
  decideRemoval,
  deleteNode,
  getMindMap,
  resetLayout,
  saveNodePosition,
  updateNode
} from '../api/mindmap'
import {
  cancelSyncJob,
  completeSyncJob,
  createSyncJob,
  getSyncJob,
  markAnimationStarted,
  openSyncJobEvents,
  skipSyncAnimation
} from '../api/syncJobs'
import { ApiError } from '../lib/apiError'
import type { AnimationState } from '../components/mindmap/MindMapCanvas'
import type { NodeEditState } from '../components/mindmap/NodeEditDialog'

const ACTIVE_JOB_KEY = 'aimindmap.activeJobId'

// §5.4：ANIMATING 超过 10 分钟视为过期，建议直接跳过
const STALE_ANIMATION_MS = 10 * 60 * 1000
// AI 调用最慢约 60 秒，给整个跟踪过程 3 分钟上限
const TRACK_TIMEOUT_MS = 3 * 60 * 1000
const POLL_INTERVAL_MS = 1500

const NON_TERMINAL_STATUSES: SyncJobStatus[] = [
  'PENDING',
  'PARSING',
  'DIFFING',
  'AI_GENERATING',
  'VALIDATING',
  // 动画由前端根据 READY 时拿到的 targetGraph 播放。
  // 轮询看到 ANIMATING 时只刷新任务信息，不能重复初始化动画。
  'ANIMATING'
]

export interface RecoveryState {
  jobId: string
  status: SyncJobStatus
  stale: boolean
}

interface Notice {
  type: 'success' | 'error' | 'info'
  text: string
}

interface MindMapStore {
  mindmap: MindMapResponse | null
  job: SyncJob | null
  animation: AnimationState | null
  starting: boolean
  nodeBusy: boolean
  editState: NodeEditState | null
  notice: Notice | null
  recovery: RecoveryState | null

  locked: () => boolean
  loadMindmap: (documentId: string) => Promise<void>
  startSync: (documentId: string, contentVersion: number) => Promise<void>
  retrySync: (documentId: string, contentVersion: number) => Promise<void>
  resumeActiveJob: (documentId: string) => Promise<void>
  continueRecoveredAnimation: () => Promise<void>
  skipRecoveredJob: () => Promise<void>
  dismissRecovery: () => void
  onAnimationDone: (jobId: string) => Promise<void>
  skipAnimation: () => Promise<void>
  cancelJob: () => Promise<void>
  requestAddChild: (parent: MindMapNode) => void
  requestEditNode: (node: MindMapNode) => void
  requestDeleteNode: (node: MindMapNode) => Promise<void>
  acceptRemoval: (node: MindMapNode) => Promise<void>
  keepNode: (node: MindMapNode) => Promise<void>
  saveNodePosition: (nodeId: string, x: number, y: number) => Promise<void>
  resetLayout: () => Promise<void>
  confirmEdit: (label: string, summary: string) => Promise<NodeActionResponse | null>
  closeEditDialog: () => void
  clearNotice: () => void
  reset: () => void
}

// 任务跟踪资源（SSE 连接、轮询定时器）不放进 React 状态
let eventSource: EventSource | null = null
let pollTimer: number | null = null
let trackDeadline = 0

function stopTracking(): void {
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
  if (pollTimer !== null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

export const useMindMapStore = create<MindMapStore>((set, get) => {
  /** 跟踪一个同步任务：SSE 优先，出错后轮询兜底；终态时转入动画/失败/完成。 */
  async function trackJob(job: SyncJob, recovered: boolean): Promise<void> {
    stopTracking()

    if (job.status === 'READY') {
      if (recovered) {
        set({ job, recovery: { jobId: job.jobId, status: 'READY', stale: false } })
        return
      }
      let started = job
      try {
        started = await markAnimationStarted(job.jobId)
      } catch (error) {
        if (!(error instanceof ApiError && error.code === 'INVALID_JOB_STATUS')) throw error
      }
      beginAnimation(set, started)
      return
    }

    if (job.status === 'ANIMATING') {
      const stale =
        !!job.animationStartedAt &&
        Date.now() - new Date(job.animationStartedAt).getTime() > STALE_ANIMATION_MS
      if (recovered) {
        set({ job, recovery: { jobId: job.jobId, status: 'ANIMATING', stale } })
        return
      }
      beginAnimation(set, job)
      return
    }

    if (job.status === 'FAILED') {
      window.localStorage.removeItem(ACTIVE_JOB_KEY)
      stopTracking()
      set({
        job,
        animation: null,
        recovery: null,
        notice: { type: 'error', text: job.errorMessage ?? '同步失败，请修改后重试' }
      })
      return
    }

    if (job.status === 'COMPLETED' || job.status === 'CANCELLED') {
      await finishJob(job)
      return
    }

    // PENDING / PARSING / DIFFING / AI_GENERATING / VALIDATING
    set({ job, recovery: null, notice: null })
    trackingStart(set, get, job.jobId)
  }

  function trackingStart(
    set: (partial: Partial<MindMapStore>) => void,
    get: () => MindMapStore,
    jobId: string
  ): void {
    trackDeadline = Date.now() + TRACK_TIMEOUT_MS

    const refreshFromServer = async (): Promise<boolean> => {
      const fresh = await getSyncJob(jobId)
      if (fresh.status && !NON_TERMINAL_STATUSES.includes(fresh.status)) {
        await trackJob(fresh, false)
        return true
      }
      set({ job: fresh })
      return false
    }

    eventSource = openSyncJobEvents(jobId, {
      onStatus: () => {
        // 状态事件只带来 status，立刻拉一次完整任务，保持进度文字准确
        void refreshFromServer().catch(() => undefined)
      },
      onReady: (fresh) => {
        void trackJob(fresh, false)
      },
      onFailed: (fresh) => {
        void trackJob(fresh, false)
      },
      onCancelled: () => {
        stopTracking()
        window.localStorage.removeItem(ACTIVE_JOB_KEY)
        set({ job: null, notice: { type: 'info', text: '已取消本次同步' } })
      },
      onCompleted: () => {
        stopTracking()
        window.localStorage.removeItem(ACTIVE_JOB_KEY)
        set({ job: null, animation: null })
      },
      onError: () => {
        // SSE 断开（代理/网络问题）时退回轮询，不影响同步本身
        if (eventSource) {
          eventSource.close()
          eventSource = null
        }
      }
    })

    pollTimer = window.setInterval(() => {
      if (Date.now() > trackDeadline) {
        stopTracking()
        set({
          job: null,
          notice: { type: 'error', text: '同步等待超时，请稍后通过“同步到思维导图”重试' }
        })
        return
      }
      // SSE 正常时也允许轮询兜底（服务器本地单实例，开销很小）
      void refreshFromServer()
        .then((finished) => {
          if (finished && pollTimer !== null) {
            window.clearInterval(pollTimer)
            pollTimer = null
          }
        })
        .catch(() => undefined)
    }, POLL_INTERVAL_MS)
  }

  async function finishJob(job: SyncJob): Promise<void> {
    stopTracking()
    window.localStorage.removeItem(ACTIVE_JOB_KEY)
    if (job.status === 'COMPLETED') {
      await get().loadMindmap(job.documentId)
    }
    set({ job: null, animation: null, recovery: null })
  }

  async function applyNodeResult(
    set: (partial: Partial<MindMapStore>) => void,
    get: () => MindMapStore,
    result: { graphVersion: number; graph: MindMapGraph },
    successText: string
  ): Promise<void> {
    const state = get()
    if (state.mindmap) {
      set({
        mindmap: { ...state.mindmap, graph: result.graph, graphVersion: result.graphVersion },
        notice: { type: 'success', text: successText }
      })
    }
  }

  return {
    mindmap: null,
    job: null,
    animation: null,
    starting: false,
    nodeBusy: false,
    editState: null,
    notice: null,
    recovery: null,

    locked: () => {
      const { job, animation } = get()
      if (animation) return true
      return !!job && LOCKED_STATUSES.includes(job.status)
    },

    loadMindmap: async (documentId) => {
      const mindmap = await getMindMap(documentId)
      set({ mindmap })
    },

    startSync: async (documentId, contentVersion) => {
      if (get().starting || get().locked()) return
      set({ starting: true, notice: null, recovery: null })
      try {
        let job: SyncJob
        try {
          job = await createSyncJob(documentId, contentVersion)
        } catch (error) {
          // §13.4：已有活动任务时不新建，直接恢复显示当前任务
          if (
            error instanceof ApiError &&
            error.status === 409 &&
            error.code === 'ACTIVE_SYNC_JOB' &&
            typeof error.body.activeJobId === 'string'
          ) {
            job = await getSyncJob(error.body.activeJobId)
          } else {
            throw error
          }
        }
        window.localStorage.setItem(ACTIVE_JOB_KEY, job.jobId)
        await trackJob(job, false)
      } catch (error) {
        stopTracking()
        set({
          notice: { type: 'error', text: error instanceof Error ? error.message : '同步失败' }
        })
      } finally {
        set({ starting: false })
      }
    },

    retrySync: async (documentId, contentVersion) => {
      await get().startSync(documentId, contentVersion)
    },

    resumeActiveJob: async (documentId) => {
      const raw = window.localStorage.getItem(ACTIVE_JOB_KEY)
      if (!raw) return
      const jobId = raw
      try {
        const job = await getSyncJob(jobId)
        if (job.documentId !== documentId) {
          window.localStorage.removeItem(ACTIVE_JOB_KEY)
          return
        }
        await trackJob(job, true)
      } catch {
        window.localStorage.removeItem(ACTIVE_JOB_KEY)
      }
    },

    continueRecoveredAnimation: async () => {
      const { recovery } = get()
      if (!recovery) return
      try {
        let job = await getSyncJob(recovery.jobId)
        if (job.status === 'READY') {
          try {
            job = await markAnimationStarted(job.jobId)
          } catch (error) {
            if (!(error instanceof ApiError && error.code === 'INVALID_JOB_STATUS')) throw error
          }
        }
        set({ recovery: null })
        beginAnimation(set, job)
      } catch (error) {
        set({
          notice: { type: 'error', text: error instanceof Error ? error.message : '恢复动画失败' }
        })
      }
    },

    skipRecoveredJob: async () => {
      const { recovery } = get()
      if (!recovery) return
      try {
        const result: CompleteJobResponse = await skipSyncAnimation(recovery.jobId)
        window.localStorage.removeItem(ACTIVE_JOB_KEY)
        set((state) => ({
          job: null,
          animation: null,
          recovery: null,
          mindmap: state.mindmap
            ? { ...state.mindmap, graph: result.graph, graphVersion: result.graphVersion }
            : state.mindmap,
          notice: { type: 'info', text: '已跳过动画并发布思维导图' }
        }))
      } catch (error) {
        set({ notice: { type: 'error', text: error instanceof Error ? error.message : '跳过失败' } })
      }
    },

    dismissRecovery: () => set({ recovery: null }),

    onAnimationDone: async (jobId) => {
      try {
        const result: CompleteJobResponse = await completeSyncJob(jobId)
        stopTracking()
        window.localStorage.removeItem(ACTIVE_JOB_KEY)
        set((state) => {
          const next: Partial<MindMapStore> = {
            job: null,
            animation: null,
            recovery: null,
            notice: { type: 'success', text: '思维导图已更新，现在可以编辑节点' }
          }
          if (state.mindmap) {
            next.mindmap = {
              ...state.mindmap,
              graph: result.graph,
              graphVersion: result.graphVersion
            }
          }
          return next
        })
      } catch (error) {
        set({
          animation: null,
          notice: { type: 'error', text: error instanceof Error ? error.message : '发布失败' }
        })
      }
    },

    skipAnimation: async () => {
      const { job, animation, recovery } = get()
      const jobId = animation?.jobId ?? recovery?.jobId ?? job?.jobId
      if (!jobId) return
      try {
        const result: CompleteJobResponse = await skipSyncAnimation(jobId)
        stopTracking()
        window.localStorage.removeItem(ACTIVE_JOB_KEY)
        set((state) => ({
          job: null,
          animation: null,
          recovery: null,
          mindmap: state.mindmap
            ? { ...state.mindmap, graph: result.graph, graphVersion: result.graphVersion }
            : state.mindmap,
          notice: { type: 'info', text: '已跳过动画并发布思维导图' }
        }))
      } catch (error) {
        set({ notice: { type: 'error', text: error instanceof Error ? error.message : '跳过失败' } })
      }
    },

    cancelJob: async () => {
      const { job, recovery } = get()
      const jobId = job?.jobId ?? recovery?.jobId
      if (!jobId) return
      try {
        await cancelSyncJob(jobId)
        stopTracking()
        window.localStorage.removeItem(ACTIVE_JOB_KEY)
        set({ job: null, animation: null, recovery: null, notice: { type: 'info', text: '已取消本次同步' } })
      } catch (error) {
        set({ notice: { type: 'error', text: error instanceof Error ? error.message : '取消失败' } })
      }
    },

    requestAddChild: (parent) => {
      set({ editState: { mode: 'add', parent } })
    },
    requestEditNode: (node) => {
      set({ editState: { mode: 'edit', node } })
    },
    closeEditDialog: () => set({ editState: null }),

    requestDeleteNode: async (node) => {
      const { mindmap } = get()
      if (!mindmap) return
      const confirmed = window.confirm(
        `确定删除节点「${node.label}」吗？它下面的子节点会一起删除。`
      )
      if (!confirmed) return
      set({ nodeBusy: true, notice: null })
      try {
        const result = await deleteNode(mindmap.id, node.id, mindmap.graphVersion)
        set((state) => ({
          mindmap: state.mindmap
            ? { ...state.mindmap, graph: result.graph, graphVersion: result.graphVersion }
            : state.mindmap,
          notice: { type: 'success', text: '节点已删除' }
        }))
      } catch (error) {
        set({ notice: { type: 'error', text: error instanceof Error ? error.message : '删除失败' } })
      } finally {
        set({ nodeBusy: false })
      }
    },

    acceptRemoval: async (node) => {
      const { mindmap } = get()
      if (!mindmap) return
      set({ nodeBusy: true, notice: null })
      try {
        const result = await decideRemoval(
          mindmap.id,
          node.id,
          'accept',
          mindmap.graphVersion
        )
        await applyNodeResult(set, get, result, '已接受删除建议')
      } catch (error) {
        set({ notice: { type: 'error', text: error instanceof Error ? error.message : '操作失败' } })
      } finally {
        set({ nodeBusy: false })
      }
    },

    keepNode: async (node) => {
      const { mindmap } = get()
      if (!mindmap) return
      set({ nodeBusy: true, notice: null })
      try {
        const result = await decideRemoval(mindmap.id, node.id, 'keep', mindmap.graphVersion)
        await applyNodeResult(set, get, result, '已保留该节点')
      } catch (error) {
        set({ notice: { type: 'error', text: error instanceof Error ? error.message : '操作失败' } })
      } finally {
        set({ nodeBusy: false })
      }
    },

    saveNodePosition: async (nodeId, x, y) => {
      const { mindmap } = get()
      if (!mindmap) return
      try {
        const result = await saveNodePosition(mindmap.id, nodeId, {
          x,
          y,
          expectedGraphVersion: mindmap.graphVersion
        })
        set((state) => ({
          mindmap: state.mindmap
            ? { ...state.mindmap, graph: result.graph, graphVersion: result.graphVersion }
            : state.mindmap
        }))
      } catch (error) {
        set({ notice: { type: 'error', text: error instanceof Error ? error.message : '保存节点位置失败' } })
      }
    },

    resetLayout: async () => {
      const { mindmap } = get()
      if (!mindmap) return
      try {
        const result = await resetLayout(mindmap.id, mindmap.graphVersion)
        set((state) => ({
          mindmap: state.mindmap
            ? { ...state.mindmap, graph: result.graph, graphVersion: result.graphVersion }
            : state.mindmap,
          notice: { type: 'success', text: '已恢复自动布局' }
        }))
      } catch (error) {
        set({ notice: { type: 'error', text: error instanceof Error ? error.message : '恢复自动布局失败' } })
      }
    },
    confirmEdit: async (label, summary): Promise<NodeActionResponse | null> => {
      const { mindmap, editState } = get()
      if (!mindmap || !editState) return null
      set({ nodeBusy: true, notice: null })
      try {
        const result =
          editState.mode === 'add'
            ? await addNode(mindmap.id, {
                parentId: editState.parent!.id,
                label,
                summary: summary || undefined,
                expectedGraphVersion: mindmap.graphVersion
              })
            : await updateNode(mindmap.id, editState.node!.id, {
                label,
                summary: summary || undefined,
                expectedGraphVersion: mindmap.graphVersion
              })
        set((state) => ({
          mindmap: state.mindmap
            ? { ...state.mindmap, graph: result.graph, graphVersion: result.graphVersion }
            : state.mindmap,
          editState: null,
          notice: {
            type: 'success',
            text: editState.mode === 'add' ? '节点已新增，并已自动同步到 Markdown' : '节点已修改'
          }
        }))
        return result
      } catch (error) {
        set({ notice: { type: 'error', text: error instanceof Error ? error.message : '操作失败' } })
      } finally {
        set({ nodeBusy: false })
      }
      return null
    },

    clearNotice: () => set({ notice: null }),

    reset: () => {
      stopTracking()
      set({
        mindmap: null,
        job: null,
        animation: null,
        starting: false,
        nodeBusy: false,
        editState: null,
        notice: null,
        recovery: null
      })
    }
  }
})

function beginAnimation(set: (partial: Partial<MindMapStore>) => void, job: SyncJob) {
  if (!job.targetGraph || !job.operations) {
    set({
      job,
      notice: { type: 'error', text: '任务数据不完整，请重新同步' }
    })
    return
  }
  const animation: AnimationState = {
    jobId: job.jobId,
    targetGraph: job.targetGraph,
    operations: job.operations as ValidatedOperation[]
  }
  set({ job, animation, recovery: null })
}