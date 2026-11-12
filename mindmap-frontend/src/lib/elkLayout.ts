// v1.1 方案 §14.4：先布局后显现。ELK.js layered（从左到右）计算最终布局。
import ELK from 'elkjs/lib/elk-api'
import type { ElkNode } from 'elkjs/lib/elk-api'
import ElkWorker from 'elkjs/lib/elk-worker.js?worker'
import type { MindMapGraph, MindMapNode, ValidatedOperation } from '../types/mindmap'
import type { Edge, Node } from '@xyflow/react'

const elk = new ELK({
  workerFactory: () => new ElkWorker()
})

export interface MindMapFlowData {
  node: MindMapNode
  rootId: string | null
  revealed: boolean
  isUpdated: boolean
  interactive?: boolean
  onAddChild?: (node: MindMapNode) => void
  onEdit?: (node: MindMapNode) => void
  onDelete?: (node: MindMapNode) => void
  onAcceptRemoval?: (node: MindMapNode) => void
  onKeepNode?: (node: MindMapNode) => void
  hasHiddenChildren?: boolean
  canAddChild?: boolean
  collapsed?: boolean
  onToggleCollapse?: (node: MindMapNode) => void
  [key: string]: unknown
}

export interface LayoutResult {
  nodes: Array<Node<MindMapFlowData>>
  edges: Edge[]
  /** 按深度分层的节点 ID（根为第 0 层） */
  layers: string[][]
}

function flatten(root: MindMapNode): MindMapNode[] {
  const result: MindMapNode[] = [root]
  for (const child of root.children) {
    result.push(...flatten(child))
  }
  return result
}

function estimateSize(
  label: string,
  isRoot: boolean
): { width: number; height: number } {
  if (isRoot) {
    // 根节点保持稍微醒目，但仍限制单行宽度，避免中心主题占用过多横向空间
    const units = visualUnits(label)
    return {
      width: Math.max(112, Math.min(210, Math.ceil(units * 15.5) + 26)),
      height: 44
    }
  }

  // 普通节点最多两行；完整内容通过节点 title 悬停查看，摘要不撑高节点
  const units = visualUnits(label)
  const maxUnitsPerLine = 11.8
  const lineCount = Math.min(2, Math.max(1, Math.ceil(units / maxUnitsPerLine)))
  return {
    width: Math.max(76, Math.min(178, Math.ceil(units * 13.1) + 22)),
    height: lineCount === 1 ? 34 : 50
  }
}

/** 粗略视觉宽度：中文/全角字符按 1，英文、数字和常见符号按约 0.58。 */
function visualUnits(text: string): number {
  return Array.from(text).reduce((sum, char) => {
    return sum + (char.charCodeAt(0) > 255 ? 1 : 0.58)
  }, 0)
}

export async function layoutGraph(
  graph: MindMapGraph,
  operations: ValidatedOperation[] | null,
  revealedIds: Set<string> | null
): Promise<LayoutResult> {
  if (!graph.tree || !graph.rootId) {
    return { nodes: [], edges: [], layers: [] }
  }

  const all = flatten(graph.tree)
  const newIds = new Set<string>()
  const updatedIds = new Set<string>()
  for (const op of operations ?? []) {
    if (op.type === 'add_node') newIds.add(op.nodeId)
    if (op.type === 'update_node') updatedIds.add(op.nodeId)
  }

  const children: ElkNode[] = all.map((node) => {
    const size = estimateSize(node.label, node.id === graph.rootId)
    return { id: node.id, ...size }
  })
  const elkEdges = all.slice(1).map((node) => {
    const parentId = findParentId(graph.tree!, node.id)
    return { id: `e-${parentId}-${node.id}`, sources: [parentId!], targets: [node.id] }
  })

  const laidOut = await elk.layout({
    id: 'root',
    layoutOptions: {
      'elk.algorithm': 'layered',
      'elk.direction': 'RIGHT',
      'elk.layered.spacing.nodeNodeBetweenLayers': '42',
      'elk.spacing.nodeNode': '16',
      'elk.layered.spacing.edgeNodeBetweenLayers': '18',
      'elk.layered.nodePlacement.strategy': 'BRANDES_KOEPF'
    },
    children,
    edges: elkEdges
  })

  const positions = new Map<string, { x: number; y: number }>()
  for (const child of laidOut.children ?? []) {
    positions.set(child.id, { x: child.x ?? 0, y: child.y ?? 0 })
  }

  const nodes: Array<Node<MindMapFlowData>> = all.map((node) => {
    const autoPosition = positions.get(node.id) ?? { x: 0, y: 0 }
    const position = node.layout?.manuallyPositioned
      ? { x: node.layout.x, y: node.layout.y }
      : autoPosition
    const isNew = newIds.has(node.id)
    return {
      id: node.id,
      position,
      data: {
        node,
        rootId: graph.rootId,
        revealed: revealedIds === null ? true : !isNew || revealedIds.has(node.id),
        isUpdated: updatedIds.has(node.id)
      },
      draggable: false,
      connectable: false,
      type: 'mindMap',
      style: { transition: 'transform 0.3s ease' }
    }
  })

  const edges: Edge[] = all
    .slice(1)
    .map((node) => {
      const parentId = findParentId(graph.tree!, node.id)!
      const edgeNew = newIds.has(node.id)
      const revealed = revealedIds === null ? true : !edgeNew || revealedIds.has(node.id)
      return {
        id: `e-${parentId}-${node.id}`,
        source: parentId,
        target: node.id,
        className: revealed ? 'mindmap-edge' : 'mindmap-edge hidden-edge',
        animated: false
      }
    })

  return { nodes, edges, layers: buildLayers(graph.tree) }
}

function findParentId(root: MindMapNode, id: string): string | null {
  for (const child of root.children) {
    if (child.id === id) return root.id
    const found = findParentId(child, id)
    if (found) return found
  }
  return null
}

function buildLayers(root: MindMapNode): string[][] {
  const layers: string[][] = []
  const walk = (node: MindMapNode, depth: number) => {
    if (!layers[depth]) layers[depth] = []
    layers[depth].push(node.id)
    for (const child of node.children) walk(child, depth + 1)
  }
  walk(root, 0)
  return layers
}