import { useEffect, useMemo, useRef, useState } from 'react'
import { Background, Controls, ReactFlow, ReactFlowProvider, useReactFlow } from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { toSvg } from 'html-to-image'
import type { Edge, Node } from '@xyflow/react'
import type { MindMapGraph, MindMapNode, ValidatedOperation } from '../../types/mindmap'
import { layoutGraph, type LayoutResult, type MindMapFlowData } from '../../lib/elkLayout'
import { downloadDataUrl, downloadTextFile, sanitizeFileName } from '../../lib/download'
import { MindMapNodeView, type MindMapFlowNode } from './MindMapNodeView'

const nodeTypes = { mindMap: MindMapNodeView }
// 根节点为深度 0；导图生成/打开后默认折叠所有子节点，用户点击 + 逐层展开
const COLLAPSIBLE_MIN_DEPTH = 0
// 节点总数超过该阈值时进入紧凑模式：只显示前三级，更深的节点不参与渲染，第三级也不能再新增子节点
const COMPACT_NODE_THRESHOLD = 100
const COMPACT_MAX_DEPTH = 3
// 导出 SVG / 大纲时等待完整树布局的最长时间（大文档 ELK 布局可能需要几秒）
const EXPORT_LAYOUT_TIMEOUT_MS = 20000

export interface AnimationState {
  jobId: string
  targetGraph: MindMapGraph
  operations: ValidatedOperation[]
}

interface Props {
  graph: MindMapGraph
  animation: AnimationState | null
  locked: boolean
  exportFileName: string
  onExportError?: (message: string) => void
  onAddChild: (node: MindMapNode) => void
  onEditNode: (node: MindMapNode) => void
  onDeleteNode: (node: MindMapNode) => void
  onAcceptRemoval: (node: MindMapNode) => void
  onKeepNode: (node: MindMapNode) => void
  onAnimationDone: (jobId: string) => void
  onSaveNodePosition: (nodeId: string, x: number, y: number) => Promise<void>
  onResetLayout: () => Promise<void>
}

function cloneNode(node: MindMapNode): MindMapNode {
  return {
    ...node,
    sourcePath: [...node.sourcePath],
    children: node.children.map(cloneNode)
  }
}

function collectDefaultCollapsed(root: MindMapNode | null): Set<string> {
  const collapsed = new Set<string>()
  const walk = (node: MindMapNode | null) => {
    if (!node) return
    // 有子节点的节点都进入折叠集合；实际是否显示展开按钮还会受紧凑模式最大深度限制
    if (node.children.length > 0) {
      collapsed.add(node.id)
    }
    node.children.forEach(walk)
  }
  walk(root)
  return collapsed
}

function countNodes(root: MindMapNode | null): number {
  if (!root) return 0
  let total = 0
  const walk = (node: MindMapNode) => {
    total += 1
    node.children.forEach(walk)
  }
  walk(root)
  return total
}

/** 根据折叠状态裁剪图。深层层级仍保留在原始 graph 中，只是不参与本次显示和布局。 */
function buildVisibleGraph(
  graph: MindMapGraph,
  collapsedIds: Set<string>,
  maxDepth: number
): {
  graph: MindMapGraph
  effectiveCollapsed: Set<string>
  collapsible: Set<string>
  depthById: Map<string, number>
} {
  if (!graph.tree) {
    return {
      graph: { ...graph, tree: null, rootId: null },
      effectiveCollapsed: new Set(),
      collapsible: new Set(),
      depthById: new Map()
    }
  }

  const effectiveCollapsed = new Set<string>()
  const collapsible = new Set<string>()
  const depthById = new Map<string, number>()
  const filterNode = (node: MindMapNode, depth: number): MindMapNode | null => {
    // 紧凑模式硬边界：超过最大显示层级的节点不参与渲染和布局（数据仍完整保留在 graph 中）
    if (depth > maxDepth) return null
    depthById.set(node.id, depth)
    if (depth >= COLLAPSIBLE_MIN_DEPTH && depth < maxDepth && node.children.length > 0) {
      collapsible.add(node.id)
    }
    const isCollapsed =
      depth >= COLLAPSIBLE_MIN_DEPTH && depth < maxDepth && collapsedIds.has(node.id)
    if (node.children.length > 0 && depth >= COLLAPSIBLE_MIN_DEPTH && isCollapsed) {
      effectiveCollapsed.add(node.id)
    }
    const children = isCollapsed
      ? []
      : node.children
          .map((child) => filterNode(child, depth + 1))
          .filter((child): child is MindMapNode => child !== null)
    return {
      ...cloneNode(node),
      children
    }
  }

  return {
    graph: { ...graph, tree: filterNode(graph.tree, 0)! },
    effectiveCollapsed,
    collapsible,
    depthById
  }
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, ms))
}

async function waitForNextFrames(): Promise<void> {
  await new Promise((resolve) => requestAnimationFrame(() => resolve(null)))
  await new Promise((resolve) => requestAnimationFrame(() => resolve(null)))
}

/** 把导图导出为可重新导入的 Markdown 大纲（只用 label，不带坐标和样式）。 */
function graphToMarkdown(root: MindMapNode | null): string {
  if (!root) return ''
  const lines: string[] = []
  const walk = (node: MindMapNode, depth: number) => {
    const label = node.label.replace(/\s+/g, ' ').trim()
    lines.push(`${'  '.repeat(depth)}- ${label}`)
    node.children.forEach((child) => walk(child, depth + 1))
  }
  walk(root, 0)
  return `${lines.join('\n')}\n`
}

function CanvasInner(props: Props) {
  const {
    graph,
    animation,
    locked,
    exportFileName,
    onExportError,
    onAddChild,
    onEditNode,
    onDeleteNode,
    onAcceptRemoval,
    onKeepNode,
    onAnimationDone,
    onSaveNodePosition,
    onResetLayout
  } = props
  const [layout, setLayout] = useState<LayoutResult | null>(null)
  const [revealed, setRevealed] = useState<Set<string>>(new Set())
  const [collapsedIds, setCollapsedIds] = useState<Set<string>>(() =>
    collectDefaultCollapsed(graph.tree)
  )
  // 导出 SVG 期间为 true：临时展开全部节点、关闭紧凑裁剪并隐藏节点操作按钮
  const [exporting, setExporting] = useState(false)
  const wrapperRef = useRef<HTMLDivElement | null>(null)
  const { fitView } = useReactFlow()

  const callbacksRef = useRef({
    onAddChild,
    onEditNode,
    onDeleteNode,
    onAcceptRemoval,
    onKeepNode,
    onAnimationDone,
    onSaveNodePosition,
    onResetLayout
  })
  callbacksRef.current = {
    onAddChild,
    onEditNode,
    onDeleteNode,
    onAcceptRemoval,
    onKeepNode,
    onAnimationDone,
    onSaveNodePosition,
    onResetLayout
  }

  const displayGraph: MindMapGraph = animation ? animation.targetGraph : graph
  const collapsedKey = Array.from(collapsedIds).sort().join(',')

  // 紧凑模式按“裁剪前”的完整节点总数判断（例如 248 个节点 -> 触发，只显示前三级）
  const totalNodeCount = useMemo(() => countNodes(displayGraph.tree), [displayGraph])
  const compact = totalNodeCount > COMPACT_NODE_THRESHOLD
  // 导出时临时放开紧凑模式深度边界，确保 SVG 包含完整导图（仅本次导出，不改数据）
  const maxDisplayDepth = exporting
    ? Number.POSITIVE_INFINITY
    : compact
      ? COMPACT_MAX_DEPTH
      : Number.POSITIVE_INFINITY

  useEffect(() => {
    if (animation) {
      // 动画播放期间临时展开可见层级，保证“逐层生成”的动画能看见；动画结束后再折叠
      setCollapsedIds(new Set())
    } else {
      setCollapsedIds(collectDefaultCollapsed(displayGraph.tree))
    }
  }, [displayGraph.rootId, animation?.jobId, compact])

  const visible = useMemo(
    () => buildVisibleGraph(displayGraph, collapsedIds, maxDisplayDepth),
    [displayGraph, collapsedKey, maxDisplayDepth]
  )

  const currentLabels = useMemo(() => {
    const map = new Map<string, string>()
    const walk = (node: MindMapNode | null): void => {
      if (!node) return
      map.set(node.id, node.label)
      node.children.forEach(walk)
    }
    walk(graph.tree)
    return map
  }, [graph])

  const visualOperations = useMemo(() => {
    const visibleIds = new Set<string>()
    const collect = (node: MindMapNode | null) => {
      if (!node) return
      visibleIds.add(node.id)
      node.children.forEach(collect)
    }
    collect(visible.graph.tree)
    const ops = animation?.operations ?? []
    return ops.filter(
      (op) =>
        visibleIds.has(op.nodeId) &&
        !(op.type === 'update_node' && currentLabels.get(op.nodeId) === op.node.label)
    )
  }, [animation, visible.graph, currentLabels])

  const newIds = useMemo(() => {
    const set = new Set<string>()
    for (const op of visualOperations) {
      if (op.type === 'add_node') set.add(op.nodeId)
    }
    return set
  }, [visualOperations])

  const graphKey = `${displayGraph.rootId}-${displayGraph.version}-${animation ? animation.jobId : 'live'}-${collapsedKey}-${compact ? 'compact' : 'full'}-${exporting ? 'export' : 'view'}-${displayGraph.updatedAt ?? ''}`
  useEffect(() => {
    let cancelled = false
    if (!visible.graph.tree) {
      setLayout(null)
      return
    }
    layoutGraph(visible.graph, animation ? visualOperations : null, animation ? new Set<string>() : null)
      .then((result) => {
        if (cancelled) return
        setRevealed(new Set())
        setLayout(result)
        window.setTimeout(() => fitView({ duration: 300, padding: 0.2, maxZoom: 1.1 }), 50)
      })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [graphKey])

  // 导出时用于判断“完整树是否已完成 ELK 布局并渲染到 DOM”
  const layoutRef = useRef<LayoutResult | null>(null)
  layoutRef.current = layout
  const visibleCountRef = useRef(0)
  visibleCountRef.current = countNodes(visible.graph.tree)

  useEffect(() => {
    if (!animation || !layout) return
    const layerGroups = layout.layers
      .map((layer) => layer.filter((id) => newIds.has(id)))
      .filter((group) => group.length > 0)
    if (layerGroups.length === 0) {
      const timer = window.setTimeout(() => {
        setCollapsedIds(collectDefaultCollapsed(animation.targetGraph.tree))
        void callbacksRef.current.onAnimationDone(animation.jobId)
      }, 200)
      return () => window.clearTimeout(timer)
    }
    const interval = newIds.size > 50 ? 120 : 200
    const timers: number[] = []
    const accumulated = new Set<string>()
    layerGroups.forEach((group, index) => {
      timers.push(
        window.setTimeout(() => {
          group.forEach((id) => accumulated.add(id))
          setRevealed(new Set(accumulated))
        }, (index + 1) * interval)
      )
    })
    timers.push(
      window.setTimeout(() => {
        setCollapsedIds(collectDefaultCollapsed(animation.targetGraph.tree))
        void callbacksRef.current.onAnimationDone(animation.jobId)
      }, (layerGroups.length + 1) * interval + 300)
    )
    return () => timers.forEach((timer) => window.clearTimeout(timer))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [layout, animation?.jobId])

  const interactive = !locked && !animation && !exporting
  const canExportSvg = !!displayGraph.tree && !locked && !animation && !exporting
  const canExportOutline = !!displayGraph.tree && !locked && !animation && !exporting

  async function handleExportSvg() {
    if (!displayGraph.tree || !canExportSvg) return
    const previousCollapsed = collapsedIds
    setExporting(true)
    try {
      // 临时展开全部节点，触发完整树的 ELK 布局
      setCollapsedIds(new Set())
      const deadline = Date.now() + EXPORT_LAYOUT_TIMEOUT_MS
      // 轮询等待：布局结果和 DOM 中渲染出的节点数都等于完整节点总数
      while (Date.now() < deadline) {
        const currentLayout = layoutRef.current
        const rendered =
          wrapperRef.current?.querySelectorAll('.react-flow__node').length ?? 0
        if (
          currentLayout &&
          visibleCountRef.current === totalNodeCount &&
          currentLayout.nodes.length === totalNodeCount &&
          rendered === totalNodeCount
        ) {
          break
        }
        await delay(100)
      }
      const finalLayout = layoutRef.current
      const finalRendered = wrapperRef.current?.querySelectorAll('.react-flow__node').length ?? 0
      if (
        !finalLayout ||
        visibleCountRef.current !== totalNodeCount ||
        finalLayout.nodes.length !== totalNodeCount ||
        finalRendered !== totalNodeCount
      ) {
        throw new Error('导图节点较多，等待完整布局超时，请稍后重试')
      }

      await waitForNextFrames()
      fitView({ duration: 0, padding: 0.15, maxZoom: 1.1 })
      await delay(350)

      // 截 React Flow 根容器（尺寸固定，fitView 后的视口变换会被完整复制），
      // 比直接截 viewport（绝对定位、宽高可能为 0）更稳定
      const flowRoot = wrapperRef.current?.querySelector<HTMLElement>('.react-flow')
      if (!flowRoot) {
        throw new Error('未找到思维导图渲染区域')
      }

      const exportHiddenClasses = [
        'react-flow__controls',
        'react-flow__attribution',
        'react-flow__handle'
      ]
      // SVG 是矢量格式：文本再长缩放也不会糊，比原来的 PNG 更适合长导图
      const dataUrl = await toSvg(flowRoot, {
        backgroundColor: '#ffffff',
        cacheBust: true,
        // 截图中不保留缩放控件、官网署名和连接点
        filter: (node) => {
          if (!(node instanceof HTMLElement)) return true
          return !exportHiddenClasses.some((className) => node.classList.contains(className))
        }
      })
      const fileName = `${sanitizeFileName(exportFileName, 'mindmap')}.svg`
      downloadDataUrl(dataUrl, fileName)
    } catch (error) {
      console.error('导出思维导图 SVG 失败', error)
      onExportError?.(error instanceof Error ? error.message : '导出 SVG 失败，请重试')
    } finally {
      // 无论成功失败都恢复用户原来的折叠状态和交互
      setCollapsedIds(previousCollapsed)
      setExporting(false)
    }
  }

  function handleExportOutline() {
    if (!displayGraph.tree || !canExportOutline) return
    try {
      const fileName = `${sanitizeFileName(exportFileName, 'mindmap')}-大纲.md`
      downloadTextFile(fileName, graphToMarkdown(displayGraph.tree))
    } catch (error) {
      console.error('导出 Markdown 大纲失败', error)
      onExportError?.(error instanceof Error ? error.message : '导出大纲失败，请重试')
    }
  }

  const nodes: Array<Node<MindMapFlowData>> = useMemo(() => {
    if (!layout) return []
    return layout.nodes.map((flowNode) => {
      const isNew = newIds.has(flowNode.id)
      const data: MindMapFlowData = {
        ...flowNode.data,
        revealed: animation ? !isNew || revealed.has(flowNode.id) : true,
        interactive,
        onAddChild: (node) => {
          // 用户准备给这个节点加子节点时，先自动展开它，避免新增后被折叠隐藏
          setCollapsedIds((previous) => {
            const next = new Set(previous)
            next.delete(node.id)
            return next
          })
          callbacksRef.current.onAddChild(node)
        },
        onEdit: callbacksRef.current.onEditNode,
        onDelete: callbacksRef.current.onDeleteNode,
        onAcceptRemoval: callbacksRef.current.onAcceptRemoval,
        onKeepNode: callbacksRef.current.onKeepNode,
        hasHiddenChildren: visible.collapsible.has(flowNode.id),
        canAddChild: !(
          compact &&
          (visible.depthById.get(flowNode.id) ?? 0) >= COMPACT_MAX_DEPTH
        ),
        collapsed: collapsedIds.has(flowNode.id),
        onToggleCollapse: (node) => {
          setCollapsedIds((previous) => {
            const next = new Set(previous)
            if (next.has(node.id)) {
              next.delete(node.id)
            } else {
              next.add(node.id)
            }
            return next
          })
        }
      }
      return { ...flowNode, data, draggable: interactive }
    })
  }, [layout, revealed, animation, newIds, interactive, visible, collapsedIds, compact])

  const edges: Edge[] = useMemo(() => {
    if (!layout) return []
    return layout.edges.map((edge) => {
      const childNew = newIds.has(edge.target)
      const edgeVisible = animation ? !childNew || revealed.has(edge.target) : true
      const baseClass = edge.className ?? 'mindmap-edge'
      return { ...edge, className: edgeVisible ? baseClass : `${baseClass} hidden-edge` }
    })
  }, [layout, revealed, animation, newIds])

  return (
    <div className="canvas-wrap" ref={wrapperRef}>
      {displayGraph.tree ? (
        <>
          <div className="canvas-toolbar layout-actions">
            <button
              type="button"
              disabled={!interactive}
              onClick={() => void callbacksRef.current.onResetLayout()}
              title="清空手动拖拽的位置，重新自动排列"
            >
              恢复自动布局
            </button>
            <button
              type="button"
              disabled={!canExportSvg}
              onClick={() => void handleExportSvg()}
              title="临时展开全部节点后导出矢量 SVG；长文本放大也不会糊"
            >
              {exporting ? '导出中...' : '导出 SVG'}
            </button>
            <button
              type="button"
              disabled={!canExportOutline}
              onClick={handleExportOutline}
              title="按当前思维导图导出 Markdown 大纲，可重新导入"
            >
              导出大纲
            </button>
            {compact ? (
              <span className="layout-hint">
                节点较多（共 {totalNodeCount} 个），为避免拥挤只显示前三级，更深的要点已自动收起
              </span>
            ) : (
              <span className="layout-hint">导图默认折叠子节点，点节点右上角 + 可逐层展开</span>
            )}
          </div>
          <ReactFlow
            nodes={nodes as MindMapFlowNode[]}
            edges={edges}
            nodeTypes={nodeTypes}
            nodesDraggable={interactive}
            nodesConnectable={false}
            elementsSelectable={!locked && !exporting}
            panOnDrag={!locked}
            zoomOnScroll
            minZoom={0.2}
            maxZoom={1.6}
            fitView
            /* 上限 1.1：只有一两个节点时 fitView 会放大到 200%，字会大得离谱，
               看起来像"缩放坏了"。限制上限后，图越少反而越"安静"。 */
            fitViewOptions={{ padding: 0.2, maxZoom: 1.1 }}
            onNodeDragStop={(_, node) => {
              void callbacksRef.current.onSaveNodePosition(
                node.id,
                node.position.x,
                node.position.y
              )
            }}
          >
            <Background />
            <Controls showInteractive={false} />
          </ReactFlow>
        </>
      ) : (
        <div className="canvas-placeholder">
          <strong>还没有思维导图</strong>
          <span>在左侧写好 Markdown，点击上方“同步到思维导图”。</span>
        </div>
      )}
    </div>
  )
}


export function MindMapCanvas(props: Props) {
  return (
    <ReactFlowProvider>
      <CanvasInner {...props} />
    </ReactFlowProvider>
  )
}