import { useEffect, useState } from 'react'
import type { MindMapNode } from '../../types/mindmap'

export interface NodeEditState {
  mode: 'add' | 'edit'
  parent?: MindMapNode
  node?: MindMapNode
}

interface Props {
  state: NodeEditState
  busy: boolean
  onConfirm: (label: string, summary: string) => void
  onCancel: () => void
}

export function NodeEditDialog({ state, busy, onConfirm, onCancel }: Props) {
  const [label, setLabel] = useState('')
  const [summary, setSummary] = useState('')

  useEffect(() => {
    if (state.mode === 'edit' && state.node) {
      setLabel(state.node.label)
      setSummary(state.node.summary ?? '')
    } else {
      setLabel('')
      setSummary('')
    }
  }, [state])

  const title = state.mode === 'add' ? '新增子节点' : '编辑节点'
  const labelTooLong = Array.from(label.trim()).length > 20

  return (
    <div className="modal-mask" onClick={onCancel}>
      <div className="modal-card" onClick={(event) => event.stopPropagation()}>
        <h3>{title}</h3>
        {state.mode === 'add' && state.parent && (
          <p className="modal-hint">挂在「{state.parent.label}」下面</p>
        )}
        <label className="modal-field">
          <span>标题（建议不超过 20 字）</span>
          <input
            // §9.4 第 10 条：标题建议不超过 20 个中文字符，前端软提示
            maxLength={100}
            value={label}
            onChange={(event) => setLabel(event.target.value)}
            placeholder="请输入节点标题"
            autoFocus
          />
          {labelTooLong && <em className="modal-warn">标题已超过 20 字，建议精简</em>}
        </label>
        <label className="modal-field">
          <span>摘要（可空）</span>
          <textarea
            maxLength={500}
            rows={3}
            value={summary}
            onChange={(event) => setSummary(event.target.value)}
            placeholder="对这个知识点的简短说明"
          />
        </label>
        <div className="modal-actions">
          <button type="button" onClick={onCancel} disabled={busy}>
            取消
          </button>
          <button
            type="button"
            className="primary"
            disabled={busy || !label.trim()}
            onClick={() => onConfirm(label.trim(), summary.trim())}
          >
            {busy ? '处理中...' : '确定'}
          </button>
        </div>
      </div>
    </div>
  )
}