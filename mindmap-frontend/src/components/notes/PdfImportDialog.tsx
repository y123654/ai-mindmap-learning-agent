import { useEffect, useRef, useState } from 'react'
import { acceptPdfDraft, getImportJob, uploadPdf } from '../../api/imports'
import type { PdfImportJob } from '../../types/pdf'

interface PdfImportDialogProps {
  onClose: () => void
  /** 草稿接受成功：父组件刷新笔记列表并打开新建的笔记 */
  onAccepted: (documentId: string) => void | Promise<void>
}

type Phase = 'select' | 'uploading' | 'ready' | 'scanned' | 'failed' | 'accepting'

/** §15.3 固定的扫描件提示文案，前端不自行改写 */
const SCANNED_MESSAGE =
  '这个 PDF 可能是扫描件或图片版资料，当前版本无法准确识别文字。后续版本可以接入 OCR 文字识别。'

const MAX_FILE_SIZE_MB = 20

/** 阶段五-B：异步上传毫秒级返回 PENDING，之后每 2 秒轮询一次任务状态。 */
const POLL_INTERVAL_MS = 2000

function baseName(fileName: string): string {
  const slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'))
  const name = slash >= 0 ? fileName.slice(slash + 1) : fileName
  const dot = name.lastIndexOf('.')
  return dot > 0 ? name.slice(0, dot) : name
}

function formatSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  return `${Math.max(1, Math.round(bytes / 1024))} KB`
}

export function PdfImportDialog({ onClose, onAccepted }: PdfImportDialogProps) {
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [file, setFile] = useState<File | null>(null)
  const [phase, setPhase] = useState<Phase>('select')
  const [job, setJob] = useState<PdfImportJob | null>(null)
  const [title, setTitle] = useState('')
  const [formError, setFormError] = useState('')

  const pollTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const mountedRef = useRef(true)

  function clearPollTimer() {
    if (pollTimerRef.current !== null) {
      clearTimeout(pollTimerRef.current)
      pollTimerRef.current = null
    }
  }

  /** 组件卸载时关停轮询，卸载后不再 setState。 */
  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      clearPollTimer()
    }
  }, [])

  function schedulePoll(jobId: string) {
    clearPollTimer()
    pollTimerRef.current = setTimeout(() => {
      void pollOnce(jobId)
    }, POLL_INTERVAL_MS)
  }

  /** PENDING/EXTRACTING 继续等；其余均为终态，停止轮询并切界面。 */
  async function pollOnce(jobId: string) {
    try {
      const latest = await getImportJob(jobId)
      if (!mountedRef.current) return
      if (latest.status === 'PENDING' || latest.status === 'EXTRACTING') {
        schedulePoll(latest.id)
        return
      }
      clearPollTimer()
      setJob(latest)
      if (latest.status === 'DRAFT_READY') {
        setPhase('ready')
      } else if (latest.status === 'SCANNED') {
        setPhase('scanned')
      } else {
        // FAILED、ACCEPTED 或其他意外状态一律按失败处理，界面不允许卡在 uploading
        setPhase('failed')
      }
    } catch (reason) {
      if (!mountedRef.current) return
      clearPollTimer()
      setFormError(reason instanceof Error ? reason.message : '查询导入任务失败，请稍后重试')
      setPhase('select')
    }
  }

  const busy = phase === 'uploading' || phase === 'accepting'

  function clearFileInput() {
    if (fileInputRef.current) fileInputRef.current.value = ''
  }

  function pickFile(next: File | null) {
    setFormError('')
    if (!next) {
      setFile(null)
      return
    }
    if (!next.name.toLowerCase().endsWith('.pdf')) {
      setFormError('请选择 .pdf 格式的文件')
      setFile(null)
      clearFileInput()
      return
    }
    if (next.size > MAX_FILE_SIZE_MB * 1024 * 1024) {
      setFormError(`PDF 文件不能超过 ${MAX_FILE_SIZE_MB}MB，当前文件 ${formatSize(next.size)}`)
      setFile(null)
      clearFileInput()
      return
    }
    setFile(next)
  }

  async function handleUpload() {
    if (!file || busy) return
    setFormError('')
    setPhase('uploading')
    try {
      const result = await uploadPdf(file)
      if (!mountedRef.current) return
      setJob(result)
      setTitle(baseName(result.originalFileName))
      if (result.status === 'DRAFT_READY') {
        setPhase('ready')
      } else if (result.status === 'SCANNED') {
        setPhase('scanned')
      } else if (result.status === 'PENDING' || result.status === 'EXTRACTING') {
        setPhase('uploading')
        schedulePoll(result.id)
      } else {
        setPhase('failed')
      }
    } catch (reason) {
      if (!mountedRef.current) return
      setFormError(reason instanceof Error ? reason.message : 'PDF 上传失败，请稍后重试')
      setPhase('select')
    }
  }

  function chooseAnother() {
    clearPollTimer()
    setFile(null)
    setJob(null)
    setTitle('')
    setFormError('')
    setPhase('select')
    clearFileInput()
  }

  async function handleAccept() {
    if (!job || phase !== 'ready' || busy) return
    const nextTitle = title.trim()
    if (!nextTitle) {
      setFormError('笔记标题不能为空')
      return
    }
    setFormError('')
    setPhase('accepting')
    try {
      const response = await acceptPdfDraft(job.id, nextTitle)
      await onAccepted(response.documentId)
    } catch (reason) {
      setFormError(reason instanceof Error ? reason.message : '接受草稿失败，请稍后重试')
      setPhase('ready')
    }
  }

  return (
    <div className="modal-mask" onClick={() => { if (!busy) onClose() }}>
      <div className="modal-card pdf-import-card" onClick={(event) => event.stopPropagation()}>
        <h3>导入 PDF</h3>
        <p className="modal-hint">
          PDF 会先在本地解析成 Markdown 草稿，请人工检查后再创建笔记。
          第一版不会根据 PDF 自动生成思维导图，创建笔记后需手动点「同步到思维导图」。
        </p>

        {formError && <div className="pdf-import-error">{formError}</div>}

        {(phase === 'select' || phase === 'uploading') && (
          <div className="pdf-import-pick">
            <label className="pdf-import-file">
              <span>选择 PDF 文件（≤ {MAX_FILE_SIZE_MB}MB、≤ 100 页）</span>
              <input
                ref={fileInputRef}
                type="file"
                accept=".pdf,application/pdf"
                disabled={busy}
                onChange={(event) => pickFile(event.target.files?.[0] ?? null)}
              />
            </label>
            {file && (
              <div className="pdf-import-fileinfo">
                <strong title={file.name}>{file.name}</strong>
                <span>{formatSize(file.size)}</span>
              </div>
            )}
            {phase === 'uploading' && (
              <div className="pdf-import-status">正在上传并解析 PDF，请稍候...</div>
            )}
            <div className="modal-actions">
              <button type="button" disabled={busy} onClick={onClose}>取消</button>
              <button
                type="button"
                className="primary"
                disabled={!file || busy}
                onClick={() => void handleUpload()}
              >
                {phase === 'uploading' ? '解析中...' : '上传并解析'}
              </button>
            </div>
          </div>
        )}

        {(phase === 'ready' || phase === 'accepting') && job && (
          <div className="pdf-import-result">
            <div className="pdf-import-meta">
              <span title={job.originalFileName}>{job.originalFileName}</span>
              {job.pageCount !== null && <em>{job.pageCount} 页</em>}
              {job.extractedTextLength !== null && <em>提取约 {job.extractedTextLength} 字</em>}
            </div>
            <label className="modal-field">
              <span>新建笔记的标题</span>
              <input
                value={title}
                maxLength={255}
                disabled={busy}
                onChange={(event) => setTitle(event.target.value)}
                placeholder="请输入笔记标题"
              />
            </label>
            <div className="modal-field">
              <span>Markdown 草稿预览（确认后可在编辑器中继续修改）</span>
              <textarea className="pdf-draft-preview" readOnly rows={14} value={job.markdownDraft ?? ''} />
            </div>
            {phase === 'accepting' && <div className="pdf-import-status">正在创建笔记...</div>}
            <div className="modal-actions">
              <button type="button" disabled={busy} onClick={chooseAnother}>重新选择</button>
              <button type="button" className="primary" disabled={busy} onClick={() => void handleAccept()}>
                {phase === 'accepting' ? '正在创建笔记...' : '接受并创建笔记'}
              </button>
            </div>
          </div>
        )}

        {phase === 'scanned' && (
          <div className="pdf-import-result">
            <div className="pdf-import-scanned">
              <strong>未能识别到有效文字</strong>
              <p>{SCANNED_MESSAGE}</p>
            </div>
            <div className="modal-actions">
              <button type="button" onClick={chooseAnother}>重新选择</button>
              <button type="button" className="primary" onClick={onClose}>关闭</button>
            </div>
          </div>
        )}

        {phase === 'failed' && job && (
          <div className="pdf-import-result">
            <div className="pdf-import-error">
              解析失败：{job.errorMessage || '文件可能已损坏或不受支持'}
              {job.errorCode && <code>（{job.errorCode}）</code>}
            </div>
            <div className="modal-actions">
              <button type="button" onClick={chooseAnother}>重新选择</button>
              <button type="button" className="primary" onClick={onClose}>关闭</button>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
