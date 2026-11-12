import { useCallback, useEffect, useMemo, useState } from 'react'
import { listAllQuestions } from '../../api/questions'
import { listDocuments } from '../../api/documents'
import type { QuestionListItem } from '../../types/question'
import type { DocumentSummaryResponse } from '../../types/document'

interface QuestionRecordsViewProps {
  onOpenDocument: (documentId: string) => void
}

interface QuestionGroup {
  documentId: string | null
  documentTitle: string
  noteIndex: number | null
  subject: string
  questions: QuestionListItem[]
  latestUpdatedAt: string
}

function difficultyText(value: string): string {
  return value === 'easy' ? '简单' : value === 'hard' ? '较难' : '中等'
}

function typeText(value: string): string {
  return value === 'single_choice' ? '单选题' : '简答题'
}

function correctKeys(question: QuestionListItem): string[] {
  if (question.answer && 'correctKeys' in question.answer) {
    const answer = question.answer as { correctKeys?: unknown }
    return Array.isArray(answer.correctKeys) ? answer.correctKeys.map(String) : []
  }
  return []
}

function referenceAnswer(question: QuestionListItem): string {
  if (question.answer && 'referenceAnswer' in question.answer) {
    const answer = question.answer as { referenceAnswer?: unknown }
    return typeof answer.referenceAnswer === 'string' ? answer.referenceAnswer : ''
  }
  return ''
}

function formatTime(value: string): string {
  return new Date(value).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}

function QuestionItem({ question, index }: { question: QuestionListItem; index: number }) {
  const keys = correctKeys(question)
  const answer = referenceAnswer(question)
  return (
    <div className="record-question-item">
      <div className="record-question-head">
        <strong>第 {index + 1} 题</strong>
        <span>{typeText(question.questionType)}</span>
        <span>{difficultyText(question.difficulty)}</span>
      </div>

      <p className="question-stem clamp-2" title={question.stem}>{question.stem}</p>
      {question.options && (
        <ul className="question-options">
          {question.options.map((option) => (
            <li
              key={option.key}
              className={keys.includes(option.key) ? 'correct-option' : ''}
              title={option.text}
            >
              {option.key}. {option.text}
            </li>
          ))}
        </ul>
      )}
      {answer && (
        <p className="question-answer clamp-2" title={answer}>
          参考答案：{answer}
        </p>
      )}
      {question.explanation && (
        <p className="question-explanation clamp-2" title={question.explanation}>
          解析：{question.explanation}
        </p>
      )}
    </div>
  )
}

function QuestionGroupCard({
  group,
  onOpenDocument
}: {
  group: QuestionGroup
  onOpenDocument: (documentId: string) => void
}) {
  // 默认折叠：一个集合只展示第 1 题，其余用“...”提示，点击后展开全部
  const [expanded, setExpanded] = useState(false)
  const singleCount = group.questions.filter((item) => item.questionType === 'single_choice').length
  const shortCount = group.questions.length - singleCount
  const hiddenCount = group.questions.length - 1
  const visibleQuestions = expanded ? group.questions : group.questions.slice(0, 1)

  return (
    <article className="question-record-card">
      <header className="record-card-header">
        <div className="record-card-heading">
          <span className="record-index-badge">
            {group.noteIndex != null ? `第 ${group.noteIndex} 篇笔记的题目集合` : '未关联笔记的题目'}
          </span>
          <h3 className="record-card-title" title={group.documentTitle}>
            {group.documentTitle}
          </h3>
        </div>
        {group.documentId != null && (
          <button
            className="primary open-note-btn"
            onClick={() => group.documentId && onOpenDocument(group.documentId)}
          >
            打开原笔记
          </button>
        )}
      </header>

      <div className="record-card-meta">
        <span>{group.subject}</span>
        <span>共 {group.questions.length} 题</span>
        {singleCount > 0 && <span>单选 {singleCount} 题</span>}
        {shortCount > 0 && <span>简答 {shortCount} 题</span>}
        {group.latestUpdatedAt && <time>最近更新：{formatTime(group.latestUpdatedAt)}</time>}
      </div>

      <div className="record-questions">
        {visibleQuestions.map((question, index) => (
          <QuestionItem key={question.id} question={question} index={index} />
        ))}

        {!expanded && hiddenCount > 0 && (
          <button
            type="button"
            className="record-more-btn"
            onClick={() => setExpanded(true)}
            title={`展开剩余 ${hiddenCount} 道题`}
          >
            <span className="record-more-dots">···</span>
            <span className="record-more-text">展开剩余 {hiddenCount} 题</span>
          </button>
        )}
        {expanded && group.questions.length > 1 && (
          <button type="button" className="record-collapse-btn" onClick={() => setExpanded(false)}>
            收起（只看第 1 题）
          </button>
        )}
      </div>
    </article>
  )
}

export function QuestionRecordsView({ onOpenDocument }: QuestionRecordsViewProps) {
  const [questions, setQuestions] = useState<QuestionListItem[]>([])
  const [documents, setDocuments] = useState<DocumentSummaryResponse[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [allQuestions, allDocuments] = await Promise.all([listAllQuestions(), listDocuments()])
      setQuestions(allQuestions)
      setDocuments(allDocuments)
      setError('')
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '获取题目记录失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  // 同一篇笔记下的题目合并成一个“题目集合”，集合顺序和序号与“笔记记录”列表保持一致
  const groups = useMemo<QuestionGroup[]>(() => {
    const byDocument = new Map<string, QuestionListItem[]>()
    const orphans: QuestionListItem[] = []

    questions.forEach((question) => {
      if (question.documentId == null) {
        orphans.push(question)
        return
      }
      const list = byDocument.get(question.documentId)
      if (list) {
        list.push(question)
      } else {
        byDocument.set(question.documentId, [question])
      }
    })

    const result: QuestionGroup[] = []

    const toGroup = (
      documentId: string | null,
      documentTitle: string,
      noteIndex: number | null,
      list: QuestionListItem[]
    ): QuestionGroup => {
      const sorted = [...list].sort((a, b) => a.createdAt.localeCompare(b.createdAt))
      const subject = sorted.map((item) => item.subject).find((value) => !!value) ?? '综合'
      const latestUpdatedAt = sorted.map((item) => item.updatedAt).sort().reverse()[0] ?? ''
      return { documentId, documentTitle, noteIndex, subject, questions: sorted, latestUpdatedAt }
    }

    documents.forEach((document, index) => {
      const list = byDocument.get(document.id)
      if (!list || list.length === 0) return
      result.push(toGroup(document.id, document.title, index + 1, list))
      byDocument.delete(document.id)
    })

    // 兜底：题目存在但笔记列表里找不到对应笔记（理论上不应出现）
    byDocument.forEach((list, documentId) => {
      result.push(toGroup(documentId, list[0]?.documentTitle || '未知笔记', null, list))
    })
    if (orphans.length > 0) {
      result.push(toGroup(null, '未关联笔记的题目', null, orphans))
    }

    return result
  }, [questions, documents])

  return (
    <section className="page-view question-records-page">
      <div className="page-title-row">
        <div>
          <h2>题目记录</h2>
          <p>题目按笔记分组成集合：默认只显示第 1 题，点击“···”可展开该集合的全部题目。</p>
        </div>
        <button onClick={() => void load()}>刷新列表</button>
      </div>

      {loading && <div className="empty-page">正在加载题目记录...</div>}
      {error && <div className="empty-page error-text">{error}</div>}
      {!loading && !error && groups.length === 0 && (
        <div className="empty-page">还没有生成过题目。请先打开笔记、同步思维导图并生成题目。</div>
      )}

      <div className="question-records-list">
        {groups.map((group) => (
          <QuestionGroupCard
            key={group.documentId ?? 'orphan-group'}
            group={group}
            onOpenDocument={onOpenDocument}
          />
        ))}
      </div>
    </section>
  )
}