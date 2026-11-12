import { useEffect, useRef, useState } from 'react'
import type { DocumentSummaryResponse } from '../../types/document'
import { PdfImportDialog } from './PdfImportDialog'

interface NotesViewProps {
  documents: DocumentSummaryResponse[]
  loading: boolean
  error: string
  currentDocumentId: string | null
  onOpenDocument: (id: string) => void
  onCreateDocument: () => void
  onRenameDocument: (id: string, title: string) => Promise<void>
  onDeleteDocument: (id: string) => Promise<void>
  /** 批量删除：一次删多篇，失败会抛错（本组件负责提示） */
  onDeleteDocuments: (ids: string[]) => Promise<void>
  /** PDF 草稿接受成功后，通知父组件刷新笔记列表 */
  onDocumentsChanged: () => void | Promise<void>
}

function formatTime(value: string): string {
  return new Date(value).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}

export function NotesView({
  documents,
  loading,
  error,
  currentDocumentId,
  onOpenDocument,
  onCreateDocument,
  onRenameDocument,
  onDeleteDocument,
  onDeleteDocuments,
  onDocumentsChanged
}: NotesViewProps) {
  const [menuOpenId, setMenuOpenId] = useState<string | null>(null)
  const [renamingId, setRenamingId] = useState<string | null>(null)
  const [draftTitle, setDraftTitle] = useState('')
  const [busyId, setBusyId] = useState<string | null>(null)
  const [actionError, setActionError] = useState('')
  const [pdfDialogOpen, setPdfDialogOpen] = useState(false)
  // 多选：只存 id，渲染时再和最新列表求交集，避免刷新后残留已删的 id
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set())
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [batchBusy, setBatchBusy] = useState(false)
  const renameInputRef = useRef<HTMLInputElement>(null)

  const selectedDocuments = documents.filter((document) => selectedIds.has(document.id))
  const selectedCount = selectedDocuments.length
  // 确认弹窗只列前 3 篇，避免标题很长时把弹窗撑爆
  const batchNames =
    selectedDocuments.slice(0, 3).map((document) => `「${document.title}」`).join('、')
    + (selectedCount > 3 ? ` 等 ${selectedCount} 篇` : '')

  function toggleSelect(id: string) {
    setSelectedIds((previous) => {
      const next = new Set(previous)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  /** 批量删除：确认后逐条删，成功后清空勾选；失败保留勾选方便重试 */
  async function handleBatchDelete() {
    if (batchBusy || selectedCount === 0) return
    const ids = selectedDocuments.map((document) => document.id)
    setBatchBusy(true)
    setActionError('')
    try {
      await onDeleteDocuments(ids)
      setSelectedIds(new Set())
      setConfirmOpen(false)
      if (renamingId !== null && ids.includes(renamingId)) cancelRename()
    } catch (reason) {
      setConfirmOpen(false)
      setActionError(reason instanceof Error ? reason.message : '批量删除失败')
    } finally {
      setBatchBusy(false)
    }
  }

  useEffect(() => {
    if (renamingId !== null) {
      renameInputRef.current?.focus()
      renameInputRef.current?.select()
    }
  }, [renamingId])

  function startRename(document: DocumentSummaryResponse) {
    setActionError('')
    setRenamingId(document.id)
    setDraftTitle(document.title)
    setMenuOpenId(null)
  }

  function cancelRename() {
    setRenamingId(null)
    setDraftTitle('')
  }

  async function submitRename(id: string) {
    const next = draftTitle.trim()
    if (busyId !== null) return
    if (!next) {
      setActionError('标题不能为空，已保留原标题')
      cancelRename()
      return
    }
    setBusyId(id)
    setActionError('')
    try {
      await onRenameDocument(id, next)
      cancelRename()
    } catch (reason) {
      setActionError(reason instanceof Error ? reason.message : '重命名失败')
    } finally {
      setBusyId(null)
    }
  }

  async function handleDelete(document: DocumentSummaryResponse) {
    setMenuOpenId(null)
    if (busyId !== null) return
    const confirmed = window.confirm(`确定删除笔记「${document.title}」吗？删除后列表中不再显示。`)
    if (!confirmed) return
    setBusyId(document.id)
    setActionError('')
    try {
      await onDeleteDocument(document.id)
      if (renamingId === document.id) cancelRename()
    } catch (reason) {
      setActionError(reason instanceof Error ? reason.message : '删除失败')
    } finally {
      setBusyId(null)
    }
  }

  async function handlePdfAccepted(documentId: string) {
    await onDocumentsChanged()
    setPdfDialogOpen(false)
    onOpenDocument(documentId)
  }

  return (
    <section className="page-view notes-page">
      <div className="page-title-row">
        <div>
          <h2>笔记记录</h2>
          <p>这里可以看到之前新建并保存过的所有笔记。</p>
        </div>
        <div className="notes-title-actions">
          <button
            type="button"
            className="button-danger"
            disabled={selectedCount === 0 || batchBusy || loading}
            title={selectedCount === 0 ? '先在卡片左上角勾选要删除的笔记' : `删除已勾选的 ${selectedCount} 篇笔记`}
            onClick={() => setConfirmOpen(true)}
          >
            {selectedCount > 0 ? `批量删除（${selectedCount}）` : '批量删除'}
          </button>
          <button type="button" onClick={() => setPdfDialogOpen(true)}>导入 PDF</button>
          <button type="button" className="primary" onClick={onCreateDocument}>新建笔记</button>
        </div>
      </div>

      {loading && <div className="empty-page">正在加载笔记列表...</div>}
      {error && <div className="empty-page error-text">{error}</div>}
      {actionError && <div className="empty-page error-text">{actionError}</div>}
      {!loading && !error && documents.length === 0 && (
        <div className="empty-page">
          <p>还没有笔记。</p>
          <button className="primary" onClick={onCreateDocument}>立即新建</button>
        </div>
      )}

      <div className="notes-grid">
        {documents.map((document, index) => {
          const menuOpen = menuOpenId === document.id
          const renaming = renamingId === document.id
          const busy = busyId === document.id
          const selected = selectedIds.has(document.id)
          return (
            <article
              key={document.id}
              className={[
                'note-card',
                currentDocumentId === document.id ? 'active' : '',
                selected ? 'is-selected' : ''
              ].filter(Boolean).join(' ')}
              onClick={() => { if (!renaming) onOpenDocument(document.id) }}
            >
              <div className="note-card-header">
                <span className="note-select" onClick={(event) => event.stopPropagation()}>
                  <input
                    type="checkbox"
                    checked={selectedIds.has(document.id)}
                    disabled={busy}
                    aria-label={`选择「${document.title}」`}
                    title="勾选后可批量删除"
                    onChange={() => toggleSelect(document.id)}
                  />
                </span>
                <h3>
                  <span className="note-index-badge" title={`笔记序号：第 ${index + 1} 篇`}>#{index + 1}</span>
                  {renaming ? (
                    <input
                      ref={renameInputRef}
                      className="note-card-title-input"
                      value={draftTitle}
                      maxLength={255}
                      onClick={(event) => event.stopPropagation()}
                      onChange={(event) => setDraftTitle(event.target.value)}
                      onKeyDown={(event) => {
                        event.stopPropagation()
                        if (event.key === 'Enter') void submitRename(document.id)
                        if (event.key === 'Escape') cancelRename()
                      }}
                      onBlur={() => {
                        // 点击“保存”按钮会先触发 blur，用微任务让按钮 onClick 先执行
                        window.setTimeout(() => {
                          if (renamingId === document.id && busyId !== document.id) cancelRename()
                        }, 150)
                      }}
                    />
                  ) : (
                    <span className="note-card-title-text" title={document.title}>{document.title}</span>
                  )}
                </h3>
                <div className="note-card-actions">
                  {currentDocumentId === document.id && <span className="note-card-open-badge">当前打开</span>}
                  <button
                    type="button"
                    className="kebab-trigger"
                    title="更多操作"
                    aria-label="更多操作"
                    disabled={busy}
                    onClick={(event) => {
                      event.stopPropagation()
                      setActionError('')
                      setMenuOpenId(menuOpen ? null : document.id)
                    }}
                  >
                    ⋮
                  </button>
                </div>
              </div>

              {menuOpen && (
                <>
                  <div className="note-card-menu-overlay" onClick={(event) => { event.stopPropagation(); setMenuOpenId(null) }} />
                  <div className="note-card-menu align-right" onClick={(event) => event.stopPropagation()}>
                    <button type="button" disabled={busy} onClick={() => startRename(document)}>重命名</button>
                    <button type="button" className="danger" disabled={busy} onClick={() => void handleDelete(document)}>删除</button>
                  </div>
                </>
              )}

              {renaming && (
                <div className="note-card-rename-row" onClick={(event) => event.stopPropagation()}>
                  <button type="button" className="primary" disabled={busy} onClick={() => void submitRename(document.id)}>
                    {busy ? '处理中...' : '保存'}
                  </button>
                  <button type="button" disabled={busy} onClick={cancelRename}>取消</button>
                </div>
              )}

              <p>{document.contentPreview || '这是一篇空白笔记，点击开始编辑。'}</p>
              <footer>
                <span>版本 {document.contentVersion}</span>
                <time>{formatTime(document.updatedAt)}</time>
              </footer>
            </article>
          )
        })}
      </div>

      {pdfDialogOpen && (
        <PdfImportDialog
          onClose={() => setPdfDialogOpen(false)}
          onAccepted={(documentId) => void handlePdfAccepted(documentId)}
        />
      )}

      {confirmOpen && (
        <div
          className="modal-mask"
          onClick={() => { if (!batchBusy) setConfirmOpen(false) }}
        >
          <div
            className="modal-card"
            role="dialog"
            aria-modal="true"
            aria-labelledby="batch-delete-title"
            onClick={(event) => event.stopPropagation()}
          >
            <h3 id="batch-delete-title">删除 {selectedCount} 篇笔记？</h3>
            <p className="modal-hint">
              {`将删除${batchNames}。删除后列表中不再显示，且无法恢复。`}
            </p>
            <div className="modal-actions">
              <button type="button" disabled={batchBusy} onClick={() => setConfirmOpen(false)}>
                取消
              </button>
              <button
                type="button"
                className="button-danger"
                disabled={batchBusy}
                onClick={() => void handleBatchDelete()}
              >
                {batchBusy ? '删除中...' : `确认删除 ${selectedCount} 篇`}
              </button>
            </div>
          </div>
        </div>
      )}
    </section>
  )
}
