import { useEffect, useRef, useState } from 'react'
import { MarkdownEditor } from '../components/editor/MarkdownEditor'
import { MindMapCanvas } from '../components/mindmap/MindMapCanvas'
import { NodeEditDialog } from '../components/mindmap/NodeEditDialog'
import { QuestionPanel } from '../components/question/QuestionPanel'
import { createDocument, getDocument, renameDocument, saveDocumentContent } from '../api/documents'
import { downloadTextFile, sanitizeFileName } from '../lib/download'
import { LOCKED_STATUSES, type MindMapNode, type SyncJobStatus } from '../types/mindmap'
import { useMindMapStore } from '../stores/mindmapStore'

const DOCUMENT_ID_KEY = 'aimindmap.currentDocumentId'

const STAGE_TEXT: Record<string, string> = {
  PENDING: '排队中...',
  PARSING: '正在解析 Markdown...',
  DIFFING: '正在比较内容变化...',
  AI_GENERATING: 'AI 正在归纳普通段落（可能需要几十秒）...',
  VALIDATING: '正在校验操作并生成目标图...',
  READY: '动画准备中...',
  ANIMATING: '正在播放动画...'
}

const CANCELLABLE_STATUSES: SyncJobStatus[] = [
  'PENDING',
  'PARSING',
  'DIFFING',
  'AI_GENERATING',
  'VALIDATING'
]

interface HomePageProps {
  initialDocumentId: string | null
  initialShowQuestions?: boolean
  onCurrentDocumentChange?: (documentId: string) => void
  onDocumentsChanged?: () => void
  onQuestionPanelVisibilityChange?: (open: boolean) => void
}

export function HomePage({
  initialDocumentId,
  initialShowQuestions = false,
  onCurrentDocumentChange,
  onDocumentsChanged,
  onQuestionPanelVisibilityChange
}: HomePageProps) {

  const [documentId, setDocumentId] = useState<string | null>(initialDocumentId)
  const [title, setTitle] = useState('未命名文档')
  const [titleDraft, setTitleDraft] = useState('未命名文档')
  const [titleSaving, setTitleSaving] = useState(false)
  const [content, setContent] = useState('')
  const [version, setVersion] = useState(1)
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [showQuestions, setShowQuestions] = useState(initialShowQuestions)
  const savedContentRef = useRef('')

  const mindmap = useMindMapStore((s) => s.mindmap)
  const job = useMindMapStore((s) => s.job)
  const animation = useMindMapStore((s) => s.animation)
  const starting = useMindMapStore((s) => s.starting)
  const nodeBusy = useMindMapStore((s) => s.nodeBusy)
  const editState = useMindMapStore((s) => s.editState)
  const notice = useMindMapStore((s) => s.notice)
  const recovery = useMindMapStore((s) => s.recovery)
  const loadMindmap = useMindMapStore((s) => s.loadMindmap)
  const startSync = useMindMapStore((s) => s.startSync)
  const retrySync = useMindMapStore((s) => s.retrySync)
  const resumeActiveJob = useMindMapStore((s) => s.resumeActiveJob)
  const onAnimationDone = useMindMapStore((s) => s.onAnimationDone)
  const skipAnimationAction = useMindMapStore((s) => s.skipAnimation)
  const continueRecoveredAnimation = useMindMapStore((s) => s.continueRecoveredAnimation)
  const cancelJobAction = useMindMapStore((s) => s.cancelJob)
  const requestAddChild = useMindMapStore((s) => s.requestAddChild)
  const requestEditNode = useMindMapStore((s) => s.requestEditNode)
  const requestDeleteNode = useMindMapStore((s) => s.requestDeleteNode)
  const acceptRemoval = useMindMapStore((s) => s.acceptRemoval)
  const keepNode = useMindMapStore((s) => s.keepNode)
  const saveNodePositionAction = useMindMapStore((s) => s.saveNodePosition)
  const resetLayoutAction = useMindMapStore((s) => s.resetLayout)
  const confirmEdit = useMindMapStore((s) => s.confirmEdit)
  const closeEditDialog = useMindMapStore((s) => s.closeEditDialog)
  const resetMindmap = useMindMapStore((s) => s.reset)

  const locked = !!animation || (!!job && LOCKED_STATUSES.includes(job.status))

  useEffect(() => {
    setTitleDraft(title)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [documentId, title])

  useEffect(() => {
    onQuestionPanelVisibilityChange?.(showQuestions)
  }, [showQuestions, onQuestionPanelVisibilityChange])

  useEffect(() => {
    if (!documentId) return
    setLoading(true)
    resetMindmap()
    Promise.all([getDocument(documentId), loadMindmap(documentId)])
      .then(([document]) => {
        setTitle(document.title)
        setContent(document.content)
        savedContentRef.current = document.content
        setVersion(document.contentVersion)
        setError('')
        return resumeActiveJob(documentId)
      })
      .catch((reason: Error) => {
        setError(reason.message)
        window.localStorage.removeItem(DOCUMENT_ID_KEY)
      })
      .finally(() => setLoading(false))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [documentId])

  async function commitTitle() {
    if (!documentId || titleSaving) return
    const next = titleDraft.trim()
    if (next === title.trim()) {
      setTitleDraft(title)
      return
    }
    setTitleSaving(true)
    setError('')
    try {
      const updated = await renameDocument(documentId, next || '未命名文档')
      setTitle(updated.title)
      setTitleDraft(updated.title)
      onDocumentsChanged?.()
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '重命名失败')
      setTitleDraft(title)
    } finally {
      setTitleSaving(false)
    }
  }

  async function persistContent(showMessage: boolean) {
    if (!documentId) return
    setSaving(true)
    setError('')
    try {
      const result = await saveDocumentContent(documentId, content, version)
      setVersion(result.contentVersion)
      savedContentRef.current = content
      if (showMessage) setMessage('保存成功，刷新页面后内容仍会保留')
      onDocumentsChanged?.()
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '保存失败')
      throw reason
    } finally {
      setSaving(false)
    }
  }

  async function handleCreate() {
    setError('')
    setMessage('')
    setSaving(true)
    try {
      const document = await createDocument('未命名文档')
      window.localStorage.setItem(DOCUMENT_ID_KEY, document.id)
      setDocumentId(document.id)
      setTitle(document.title)
      setContent(document.content)
      savedContentRef.current = ''
      setVersion(document.contentVersion)
      setMessage('新文档已创建')
      onCurrentDocumentChange?.(document.id)
      onDocumentsChanged?.()
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '新建文档失败')
    } finally {
      setSaving(false)
    }
  }

  async function handleSync() {
    if (!documentId) return
    setMessage('')
    setError('')
    try {
      // 同步基于服务器上已保存的 Markdown；有未保存修改时先保存
      if (content !== savedContentRef.current) {
        await persistContent(false)
      }
      await startSync(documentId, version)
    } catch {
      // 错误已经写入 store.notice
    }
  }

  async function handleRetry() {
    if (!documentId) return
    if (content !== savedContentRef.current) {
      await persistContent(false)
    }
    await retrySync(documentId, version)
  }

  function handleExportMarkdown() {
    try {
      const baseName = sanitizeFileName(titleDraft || title, 'mindmap-note')
      downloadTextFile(`${baseName}.md`, content)
    } catch (reason) {
      console.error('导出 Markdown 失败', reason)
      setError('导出 Markdown 失败，请重试')
    }
  }

  const dirty = content !== savedContentRef.current
  const syncing = starting || locked
  const graph = mindmap?.graph ?? { rootId: null, version: 1, tree: null }
  const cancellable =
    !!job && !animation && CANCELLABLE_STATUSES.includes(job.status)

  return (
    <>
      <header className="topbar home-topbar">
        <div>
          <h1>AI 智能思维导图</h1>
          <p>Markdown 笔记、思维导图、AI 段落归纳和题目生成</p>
        </div>
        <div className="topbar-actions">
            <button onClick={handleCreate} disabled={saving || starting}>
              新建文档
            </button>
            <button
              onClick={() => void persistContent(true)}
              disabled={!documentId || saving || loading || locked}
            >
              {saving ? '保存中...' : dirty ? '保存 Markdown *' : '保存 Markdown'}
            </button>
            <button
              onClick={handleExportMarkdown}
              disabled={loading}
              title="把左侧当前 Markdown 内容下载为 .md 文件（未保存的修改也会导出）"
            >
              导出
            </button>
            <button
              className="primary"
              onClick={() => void handleSync()}
              disabled={!documentId || loading || syncing}
              title="把当前 Markdown 同步生成思维导图"
            >
              {animation
                ? '动画播放中...'
                : starting
                  ? '生成中...'
                  : locked
                    ? '同步中...'
                    : '同步到思维导图'}
            </button>
            {animation && (
              <button onClick={skipAnimationAction} disabled={nodeBusy}>
                跳过动画
              </button>
            )}
            {cancellable && (
              <button onClick={cancelJobAction} disabled={nodeBusy}>
                取消同步
              </button>
            )}
            <button onClick={() => setShowQuestions((value) => !value)} disabled={!documentId}>
              {showQuestions ? '收起题目面板' : '生成题目'}
            </button>
        </div>
      </header>

      <main className="workspace">
        <section className="panel editor-panel">
          <div className="panel-header">
            <input
              className="note-title-input"
              value={titleDraft}
              disabled={!documentId || loading || titleSaving}
              title="修改笔记名称，失焦或按 Enter 保存，Esc 取消"
              onChange={(event) => setTitleDraft(event.target.value)}
              onBlur={() => void commitTitle()}
              onKeyDown={(event) => {
                if (event.key === 'Enter') event.currentTarget.blur()
                if (event.key === 'Escape') {
                  setTitleDraft(title)
                  event.currentTarget.blur()
                }
              }}
            />
            <span>Markdown 版本：{version}{locked ? ' · 已锁定' : ''}</span>
          </div>
          <MarkdownEditor
            value={content}
            onChange={(next) => {
              setContent(next)
              if (message) setMessage('')
            }}
            editable={!loading && !saving && !locked}
          />
        </section>

        <section className="panel canvas-panel">
          <div className="panel-header">
            <h2>思维导图</h2>
            <span>
              {mindmap ? `图版本：${mindmap.graphVersion}` : 'React Flow 画布'}
              {locked ? ' · 锁定中' : ' · 可编辑'}
            </span>
          </div>
          {documentId && (
            <MindMapCanvas
              graph={graph}
              animation={animation}
              locked={locked}
              exportFileName={title}
              onExportError={setError}
              onAddChild={(node: MindMapNode) => requestAddChild(node)}
              onEditNode={(node: MindMapNode) => requestEditNode(node)}
              onDeleteNode={(node: MindMapNode) => void requestDeleteNode(node)}
              onAcceptRemoval={(node: MindMapNode) => void acceptRemoval(node)}
              onKeepNode={(node: MindMapNode) => void keepNode(node)}
              onAnimationDone={(jobId) => void onAnimationDone(jobId)}
              onSaveNodePosition={(nodeId, x, y) => saveNodePositionAction(nodeId, x, y)}
              onResetLayout={() => resetLayoutAction()}
            />
          )}
        </section>
      </main>

      {showQuestions && documentId && mindmap && (
        <QuestionPanel mindmapId={mindmap.id} graph={mindmap.graph} />
      )}

      <footer className="statusbar">
        {loading && <span className="info">正在加载文档...</span>}
        {recovery && (
          <span className="info recovery-bar">
            检测到上次动画{recovery.status === 'ANIMATING' ? '播放中' : '已就绪'}
            {recovery.stale ? '（已超过 10 分钟）' : ''}：
            <button className="link-button" onClick={() => void continueRecoveredAnimation()}>
              继续播放动画
            </button>
            <button className="link-button" onClick={() => void skipAnimationAction()}>
              跳过动画直接发布
            </button>
          </span>
        )}
        {job?.status === 'FAILED' && (
          <span className="error">
            同步失败：{job.errorMessage ?? '未知原因'}
            <button className="link-button" onClick={() => void handleRetry()}>
              重试
            </button>
          </span>
        )}
        {!recovery && job && job.status !== 'FAILED' && !animation && STAGE_TEXT[job.status] && (
          <span className="info">{STAGE_TEXT[job.status]}</span>
        )}
        {animation && <span className="info">正在播放思维导图生成动画，期间不能编辑...</span>}
        {starting && !animation && !job && (
          <span className="info">正在解析 Markdown 并生成思维导图...</span>
        )}
        {message && <span className="success">{message}</span>}
        {notice && (
          <span className={notice.type === 'error' ? 'error' : notice.type === 'success' ? 'success' : 'info'}>
            {notice.text}
          </span>
        )}
        {error && <span className="error">{error}</span>}
        {!documentId && !loading && <span className="info">请点击左侧“＋”或顶部“新建文档”开始。</span>}
      </footer>

      {editState && (
        <NodeEditDialog
          state={editState}
          busy={nodeBusy}
          onConfirm={async (label, summary) => {
                const result = await confirmEdit(label, summary)
                if (result?.documentVersion != null && result.documentContent != null) {
                  setContent(result.documentContent)
                  setVersion(result.documentVersion)
                  savedContentRef.current = result.documentContent
                }
              }}
          onCancel={closeEditDialog}
        />
      )}
    </>
  )
}
