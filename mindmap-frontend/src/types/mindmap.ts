// v1.1 方案 §14.2 TypeScript 类型

export type NodeSourceType = 'markdown' | 'manual' | 'ai_enhanced'

export type NodeStatus = 'active' | 'removed_suggested'

export interface MindMapNode {
  id: string
  label: string
  summary: string | null
  sourceType: NodeSourceType
  sourceBlockId: string | null
  sourceAnchor: string | null
  sourcePath: string[]
  manuallyEdited: boolean
  manualLocked: boolean
  status: NodeStatus
  layout: NodeLayout | null
  createdAt?: string
  updatedAt?: string
  children: MindMapNode[]
}

export interface NodeLayout {
  x: number
  y: number
  manuallyPositioned: boolean
}

export interface MindMapGraph {
  rootId: string | null
  version: number
  tree: MindMapNode | null
  updatedAt?: string
}

export interface MindMapResponse {
  id: string
  documentId: string
  graph: MindMapGraph
  graphVersion: number
  syncStatus: string
  updatedAt: string
}

export type SyncJobStatus =
  | 'PENDING'
  | 'PARSING'
  | 'DIFFING'
  | 'AI_GENERATING'
  | 'VALIDATING'
  | 'READY'
  | 'ANIMATING'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'

// §14.3 锁定状态
export const LOCKED_STATUSES: SyncJobStatus[] = [
  'PARSING',
  'DIFFING',
  'AI_GENERATING',
  'VALIDATING',
  'READY',
  'ANIMATING'
]

export type OperationType =
  | 'add_node'
  | 'update_node'
  | 'propose_remove_source_node'

export interface ValidatedOperation {
  type: OperationType
  nodeId: string
  parentId: string | null
  afterNodeId: string | null
  node: MindMapNode
  reason: string | null
}

export interface ChangeSummary {
  added: string[]
  changed: Array<{ from: string; to: string }>
  removed: string[]
  unchanged: number
  uncertain: Array<{ from: string; to: string }>
  ai?: {
    provider: string
    model: string
    elapsedMillis: number
    promptTokens: number
    completionTokens: number
    totalTokens: number
  }
}

export interface SyncJob {
  jobId: string
  documentId: string
  mindmapId: string
  status: SyncJobStatus
  baseContentVersion: number | null
  targetContentVersion: number
  baseGraphVersion: number | null
  targetGraphVersion: number | null
  changeSummary: ChangeSummary | null
  operations: ValidatedOperation[] | null
  targetGraph: MindMapGraph | null
  errorCode: string | null
  errorMessage: string | null
  createdAt: string
  readyAt: string | null
  animationStartedAt: string | null
  completedAt: string | null
  failedAt: string | null
}

export interface CompleteJobResponse {
  jobId: string
  status: SyncJobStatus
  graphVersion: number
  graph: MindMapGraph
}

export interface NodeActionResponse {
  graphVersion: number
  graph: MindMapGraph
  documentContent?: string | null
  documentVersion?: number | null
}