import { Handle, Position, type Node, type NodeProps } from '@xyflow/react'
import type { MindMapFlowData } from '../../lib/elkLayout'

export type MindMapFlowNode = Node<MindMapFlowData, 'mindMap'>

export function MindMapNodeView({ data }: NodeProps<MindMapFlowNode>) {
  const {
    node,
    revealed,
    isUpdated,
    interactive,
    onAddChild,
    onEdit,
    onDelete,
    onAcceptRemoval,
    onKeepNode,
    hasHiddenChildren,
    collapsed,
    onToggleCollapse
  } = data
  const isRoot = node.id === data.rootId
  const branchIndex = data.branchIndex
  const branchAttribute =
    !isRoot && branchIndex !== undefined && branchIndex >= 0 ? branchIndex : undefined
  const isSuggested = node.status === 'removed_suggested'
  // 紧凑模式（节点总数 > 100）下第三级节点不允许再新增子节点：更深内容不显示
  const canAddChild = data.canAddChild !== false
  const classes = [
    'mindmap-node',
    isRoot ? 'is-root' : '',
    revealed ? 'is-revealed' : 'is-hidden',
    isUpdated ? 'is-updated' : '',
    node.sourceType === 'manual' ? 'is-manual' : '',
    node.sourceType === 'ai_enhanced' ? 'is-ai' : '',
    isSuggested ? 'is-suggested' : ''
  ]
    .filter(Boolean)
    .join(' ')

  return (
    <div className={classes} data-branch={branchAttribute}>
      <Handle type="target" position={Position.Left} />
      {hasHiddenChildren && interactive && (
      <button
        type="button"
        className="mindmap-node-expand"
        title={collapsed ? '展开下一层节点' : '折叠子节点'}
        onClick={(event) => {
          event.stopPropagation()
          onToggleCollapse?.(node)
        }}
      >
        {collapsed ? '+' : '−'}
      </button>
    )}

    <div
        className="mindmap-node-body"
        title={node.summary ? `${node.label}

${node.summary}` : node.label}
      >
        <div className="mindmap-node-label">{node.label}</div>
        {node.summary && <div className="mindmap-node-summary">{node.summary}</div>}
      </div>

      {/* §9.3 删除建议：虚线黄色样式，接受或保留后才变化；动画期间不显示按钮 */}
      {isSuggested && interactive && (
        <div className="mindmap-node-decisions">
          <button
            type="button"
            className="decision-accept"
            title="接受删除（含子节点）"
            onClick={(event) => {
              event.stopPropagation()
              onAcceptRemoval?.(node)
            }}
          >
            接受删除
          </button>
          <button
            type="button"
            className="decision-keep"
            title="保留该节点"
            onClick={(event) => {
              event.stopPropagation()
              onKeepNode?.(node)
            }}
          >
            保留
          </button>
        </div>
      )}

      {!isSuggested && interactive && (
        <div className="mindmap-node-actions">
          {canAddChild && (
            <button
              type="button"
              title="新增子节点"
              onClick={(event) => {
                event.stopPropagation()
                onAddChild?.(node)
              }}
            >
              ＋
            </button>
          )}
          <button
            type="button"
            title="改名 / 摘要"
            onClick={(event) => {
              event.stopPropagation()
              onEdit?.(node)
            }}
          >
            ✎
          </button>
          {!isRoot && (
            <button
              type="button"
              title="删除节点（含子节点）"
              className="danger"
              onClick={(event) => {
                event.stopPropagation()
                onDelete?.(node)
              }}
            >
              🗑
            </button>
          )}
        </div>
      )}
      <Handle type="source" position={Position.Right} />
    </div>
  )
}