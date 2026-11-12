import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  createQuestionGenerationJob,
  getQuestionGenerationJob,
  listQuestions,
  regenerateQuestion
} from '../../api/questions'
import type { Difficulty, Question, QuestionType } from '../../types/question'
import type { MindMapGraph, MindMapNode } from '../../types/mindmap'

interface QuestionPanelProps {
  mindmapId: string | null
  graph: MindMapGraph
}

const JOB_TERMINAL_STATUSES = new Set(['COMPLETED', 'FAILED'])

function flattenNodes(node: MindMapNode | null | undefined, output: MindMapNode[] = []): MindMapNode[] {
  if (!node) return output
  output.push(node)
  node.children.forEach((child) => flattenNodes(child, output))
  return output
}

function getCorrectKeys(question: Question): string[] {
  if (question.answer && 'correctKeys' in question.answer) {
    const answer = question.answer as { correctKeys?: unknown }
    return Array.isArray(answer.correctKeys) ? answer.correctKeys.map(String) : []
  }
  return []
}

function getReferenceAnswer(question: Question): string {
  if (question.answer && 'referenceAnswer' in question.answer) {
    const answer = question.answer as { referenceAnswer?: unknown }
    return typeof answer.referenceAnswer === 'string' ? answer.referenceAnswer : ''
  }
  return ''
}

export function QuestionPanel({ mindmapId, graph }: QuestionPanelProps) {
  const [subject, setSubject] = useState('综合')
  const [difficulty, setDifficulty] = useState<Difficulty>('medium')
  const [count, setCount] = useState(5)
  const [includeSingle, setIncludeSingle] = useState(true)
  const [includeShort, setIncludeShort] = useState(true)
  const [questions, setQuestions] = useState<Question[]>([])
  const [jobStatus, setJobStatus] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [regeneratingId, setRegeneratingId] = useState<string | null>(null)
  const timerRef = useRef<number | null>(null)

  const nodes = useMemo(() => flattenNodes(graph.tree), [graph.tree])
  const nodeLabelById = useMemo(() => {
    const map = new Map<string, string>()
    nodes.forEach((node) => map.set(node.id, node.label))
    return map
  }, [nodes])

  const refreshQuestions = useCallback(async (id: string) => {
    try {
      setQuestions(await listQuestions(id))
      setError('')
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '获取题目失败')
    }
  }, [])

  useEffect(() => {
    if (!mindmapId) {
      setQuestions([])
      return
    }
    void refreshQuestions(mindmapId)
  }, [mindmapId, refreshQuestions])

  useEffect(() => {
    return () => {
      if (timerRef.current !== null) window.clearInterval(timerRef.current)
    }
  }, [])

  async function handleGenerate() {
    if (!mindmapId) return
    const questionTypes: QuestionType[] = []
    if (includeSingle) questionTypes.push('single_choice')
    if (includeShort) questionTypes.push('short_answer')
    if (questionTypes.length === 0) {
      setError('请至少选择一种题型')
      return
    }
    setBusy(true)
    setError('')
    setJobStatus('正在创建题目任务...')
    try {
      const job = await createQuestionGenerationJob(mindmapId, {
        subject,
        questionTypes,
        difficulty,
        count
      })
      setJobStatus('AI 正在根据最新思维导图出题，请稍候...')
      timerRef.current = window.setInterval(() => {
        void pollJob(job.jobId)
      }, 900)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '创建题目任务失败')
      setJobStatus('')
      setBusy(false)
    }
  }

  async function pollJob(jobId: string) {
    try {
      const job = await getQuestionGenerationJob(jobId)
      if (!JOB_TERMINAL_STATUSES.has(job.status)) {
        setJobStatus(`AI 正在出题（${job.status}）...`)
        return
      }
      if (timerRef.current !== null) {
        window.clearInterval(timerRef.current)
        timerRef.current = null
      }
      setBusy(false)
      if (job.status === 'COMPLETED') {
        setJobStatus(`题目生成完成，本次新增 ${job.resultQuestionIds.length} 道题`)
        if (mindmapId) await refreshQuestions(mindmapId)
      } else {
        setJobStatus('')
        setError(job.errorMessage || '题目生成失败')
      }
    } catch (reason) {
      if (timerRef.current !== null) window.clearInterval(timerRef.current)
      timerRef.current = null
      setBusy(false)
      setJobStatus('')
      setError(reason instanceof Error ? reason.message : '查询题目任务失败')
    }
  }

  async function handleRegenerate(question: Question) {
    setError('')
    setRegeneratingId(question.id)
    try {
      await regenerateQuestion(question.id)
      if (mindmapId) await refreshQuestions(mindmapId)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '重新生成失败')
    } finally {
      setRegeneratingId(null)
    }
  }

  const hasGraph = !!graph.tree && nodes.length > 0
  const correctKeys = new Map<string, string[]>()

  return (
    <section className="question-panel panel">
      <div className="panel-header question-panel-header">
        <h2>题目面板</h2>
        <span>AI 根据最新思维导图生成；如不符合预期，可重新生成此题</span>
      </div>

      <div className="question-panel-body">
        <div className="question-form">
          <label>
            学科
            <input value={subject} onChange={(e) => setSubject(e.target.value)} />
          </label>
          <label>
            难度
            <select value={difficulty} onChange={(e) => setDifficulty(e.target.value as Difficulty)}>
              <option value="easy">简单</option>
              <option value="medium">中等</option>
              <option value="hard">较难</option>
            </select>
          </label>
          <label>
            数量
            <select value={count} onChange={(e) => setCount(Number(e.target.value))}>
              {[1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map((value) => (
                <option key={value} value={value}>{value} 道</option>
              ))}
            </select>
          </label>
          <label className="check-label">
            <input type="checkbox" checked={includeSingle} onChange={(e) => setIncludeSingle(e.target.checked)} />
            单选题
          </label>
          <label className="check-label">
            <input type="checkbox" checked={includeShort} onChange={(e) => setIncludeShort(e.target.checked)} />
            简答题
          </label>
          <button className="primary" onClick={handleGenerate} disabled={!mindmapId || !hasGraph || busy}>
            {busy ? '生成中...' : '生成题目'}
          </button>
        </div>

        {!hasGraph && <p className="info">请先保存 Markdown 并同步思维导图，然后再生成题目。</p>}
        {jobStatus && <p className="success">{jobStatus}</p>}
        {error && <p className="error">{error}</p>}

        <div className="question-list">
          {questions.length === 0 && <p className="info">还没有题目。生成后会显示在这里。</p>}
          {questions.map((question, index) => {
            const keys = getCorrectKeys(question)
            correctKeys.set(question.id, keys)
            const referenceAnswer = getReferenceAnswer(question)
            return (
              <article key={question.id} className="question-card">
                <div className="question-card-title">
                  <strong>第 {index + 1} 题</strong>
                  <span>{question.questionType === 'single_choice' ? '单选题' : '简答题'}</span>
                  <span>{question.difficulty === 'easy' ? '简单' : question.difficulty === 'hard' ? '较难' : '中等'}</span>
                </div>

                <p className="question-stem">{question.stem}</p>
                {question.options && (
                  <ul className="question-options">
                    {question.options.map((option) => (
                      <li key={option.key} className={keys.includes(option.key) ? 'correct-option' : ''}>
                        {option.key}. {option.text}
                      </li>
                    ))}
                  </ul>
                )}
                {referenceAnswer && <p className="question-answer">参考答案：{referenceAnswer}</p>}
                {question.explanation && <p className="question-explanation">解析：{question.explanation}</p>}
                <div className="question-node-tags">
                  {question.nodeIds.map((id) => (
                    <span key={id}>{nodeLabelById.get(id) ?? id}</span>
                  ))}
                </div>
                <div className="question-actions">
                  <button onClick={() => void handleRegenerate(question)} disabled={regeneratingId === question.id}>
                    {regeneratingId === question.id ? '重新生成中...' : '重新生成此题'}
                  </button>
                </div>
              </article>
            )
          })}
        </div>
      </div>
    </section>
  )
}
