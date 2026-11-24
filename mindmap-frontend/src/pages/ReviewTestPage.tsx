import { useCallback, useEffect, useMemo, useState } from 'react'
import { listAllQuestions } from '../api/questions'
import type { QuestionListItem } from '../types/question'

// 题数必须 ≥10 且是 100 的因数，保证每题分值都是整数（10 / 5 / 4 / 2 / 1）
const QUESTION_COUNTS = [10, 20, 25, 50, 100] as const
const MIN_QUESTION_COUNT = 10

type ReviewStage = 'start' | 'quiz' | 'result'

interface ReviewAnswer {
  selectedKey: string | null
  text: string
  submitted: boolean
  selfCorrect: boolean | null
}

const EMPTY_ANSWER: ReviewAnswer = {
  selectedKey: null,
  text: '',
  submitted: false,
  selfCorrect: null
}

/** Fisher-Yates 洗牌：返回新数组，不改动原题库 */
function shuffle<T>(items: T[]): T[] {
  const result = [...items]
  for (let i = result.length - 1; i > 0; i -= 1) {
    const j = Math.floor(Math.random() * (i + 1))
    ;[result[i], result[j]] = [result[j], result[i]]
  }
  return result
}

/** 两种题型以外的数据不参与练习（后端目前只会产这两种） */
function isReviewable(question: QuestionListItem): boolean {
  return question.questionType === 'single_choice' || question.questionType === 'short_answer'
}

/**
 * 分层抽样：先按题型分组，两种都有时各保底 1 道，
 * 其余名额按两类题库的数量比例分配；某类不够就由另一类补足。
 * 同一套题里不会重复（每个池子只从头切片，最后整体再洗一次顺序）。
 */
function pickQuestions(pool: QuestionListItem[], total: number): QuestionListItem[] {
  const singlePool = shuffle(pool.filter((item) => item.questionType === 'single_choice'))
  const shortPool = shuffle(pool.filter((item) => item.questionType === 'short_answer'))
  const hasBoth = singlePool.length > 0 && shortPool.length > 0

  const singleQuota = hasBoth
    ? Math.max(1, Math.round((total * singlePool.length) / pool.length))
    : Math.min(singlePool.length, total)
  let singleTake = Math.min(singlePool.length, singleQuota)
  let shortTake = total - singleTake

  if (shortTake > shortPool.length) {
    shortTake = shortPool.length
    singleTake = total - shortTake
  }
  // 比例四舍五入可能把名额全给了一类，这里兜住"两种都有时各至少 1 道"
  if (hasBoth && shortTake < 1) {
    shortTake = 1
    singleTake = total - 1
  }

  const picked = [...singlePool.slice(0, singleTake), ...shortPool.slice(0, shortTake)]

  // 取整误差最多让总数差 1~2 道，从剩余题库补齐，保证题数与用户选的一致
  if (picked.length < total) {
    const pickedIds = new Set(picked.map((item) => item.id))
    picked.push(
      ...shuffle(pool.filter((item) => !pickedIds.has(item.id))).slice(0, total - picked.length)
    )
  }
  return shuffle(picked)
}

/** 单选题的正确选项；数据里没给正确答案时返回空数组 */
function correctKeys(question: QuestionListItem): string[] {
  const answer = question.answer
  if (answer && 'correctKeys' in answer) {
    const value = (answer as { correctKeys?: unknown }).correctKeys
    return Array.isArray(value) ? value.map(String) : []
  }
  return []
}

/** 填空题的参考答案 */
function referenceAnswer(question: QuestionListItem): string {
  const answer = question.answer
  if (answer && 'referenceAnswer' in answer) {
    const value = (answer as { referenceAnswer?: unknown }).referenceAnswer
    return typeof value === 'string' ? value : ''
  }
  return ''
}

function optionLabel(question: QuestionListItem, key: string): string {
  const option = question.options?.find((item) => item.key === key)
  return option ? `${option.key}. ${option.text}` : key
}

function correctAnswerText(question: QuestionListItem): string {
  if (question.questionType === 'single_choice') {
    const keys = correctKeys(question)
    return keys.length > 0 ? keys.map((key) => optionLabel(question, key)).join('；') : '—'
  }
  return referenceAnswer(question) || '—'
}

function userAnswerText(question: QuestionListItem, answer: ReviewAnswer): string {
  if (question.questionType === 'single_choice') {
    return answer.selectedKey ? optionLabel(question, answer.selectedKey) : '未作答'
  }
  return answer.text.trim() || '未作答'
}

function isAnswerCorrect(question: QuestionListItem, answer: ReviewAnswer): boolean {
  if (question.questionType === 'single_choice') {
    return answer.selectedKey !== null && correctKeys(question).includes(answer.selectedKey)
  }
  return answer.selfCorrect === true
}

export function ReviewTestPage() {
  const [questions, setQuestions] = useState<QuestionListItem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [stage, setStage] = useState<ReviewStage>('start')
  const [selectedCount, setSelectedCount] = useState<number>(MIN_QUESTION_COUNT)
  const [sample, setSample] = useState<QuestionListItem[]>([])
  const [questionIndex, setQuestionIndex] = useState(0)
  const [answers, setAnswers] = useState<Record<string, ReviewAnswer>>({})
  const [onlyWrong, setOnlyWrong] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const all = await listAllQuestions()
      setQuestions(all.filter(isReviewable))
      setError('')
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '获取题目失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const singleCount = useMemo(
    () => questions.filter((item) => item.questionType === 'single_choice').length,
    [questions]
  )
  const shortCount = questions.length - singleCount
  const points = 100 / selectedCount

  const currentQuestion = sample[questionIndex]
  const currentAnswer = currentQuestion ? answers[currentQuestion.id] ?? EMPTY_ANSWER : EMPTY_ANSWER

  const patchAnswer = (questionId: string, patch: Partial<ReviewAnswer>) => {
    setAnswers((current) => ({
      ...current,
      [questionId]: { ...(current[questionId] ?? EMPTY_ANSWER), ...patch }
    }))
  }

  const startPractice = () => {
    setSample(pickQuestions(questions, selectedCount))
    setQuestionIndex(0)
    setAnswers({})
    setOnlyWrong(false)
    setStage('quiz')
  }

  const restart = () => {
    setStage('start')
    setSample([])
    setQuestionIndex(0)
    setAnswers({})
    setOnlyWrong(false)
  }

  /**
   * 单选必须先点一个选项；填空必须先提交并自评。
   * 判分统一放在结果页算，这里只负责挡住"空着进下一题"。
   */
  const canAdvance = currentQuestion
    ? currentQuestion.questionType === 'single_choice'
      ? currentAnswer.selectedKey !== null
      : currentAnswer.submitted && currentAnswer.selfCorrect !== null
    : false

  const advanceHint = !currentQuestion || canAdvance
    ? ''
    : currentQuestion.questionType === 'single_choice'
      ? '请先选择一个选项'
      : currentAnswer.submitted
        ? '请先完成自评'
        : '请先提交作答'

  const goNext = () => {
    if (questionIndex < sample.length - 1) {
      setQuestionIndex(questionIndex + 1)
      return
    }
    setOnlyWrong(false)
    setStage('result')
  }

  const goPrev = () => {
    if (questionIndex > 0) {
      setQuestionIndex(questionIndex - 1)
    }
  }

  // 结果页统一判分：单选对 correctKeys，填空以用户自评为准
  const results = useMemo(
    () =>
      sample.map((question, index) => {
        const answer = answers[question.id] ?? EMPTY_ANSWER
        return { question, index, answer, correct: isAnswerCorrect(question, answer) }
      }),
    [sample, answers]
  )
  const correctCount = results.filter((row) => row.correct).length
  const score = correctCount * (sample.length > 0 ? 100 / sample.length : 0)
  const visibleResults = onlyWrong ? results.filter((row) => !row.correct) : results

  return (
    <main className="page-workspace">
      <section className="page-view review-page">
        {loading && <div className="review-loading">正在读取题库…</div>}

        {!loading && error && (
          <div className="review-empty">
            <p className="review-error">{error}</p>
            <button type="button" onClick={() => void load()}>重新加载</button>
          </div>
        )}

        {!loading && !error && questions.length < MIN_QUESTION_COUNT && (
          <div className="review-empty">
            <h2 className="review-empty-title">暂时无法开始练习</h2>
            <p className="review-empty-text">
              题目不足 10 道（当前 {questions.length} 道），请先到「题目记录」生成一些题目。
            </p>
            <p className="review-empty-hint">
              提示：在首页打开一篇笔记并同步思维导图后，用底部的题目面板生成题目；生成完回到「复习测试」即可开始。
            </p>
          </div>
        )}

        {!loading && !error && questions.length >= MIN_QUESTION_COUNT && stage === 'start' && (
          <>
            <div className="page-title-row">
              <div>
                <h2>复习测试</h2>
                <p>随机抽题、满分 100 分的练习。成绩不保存，刷新即清空。</p>
              </div>
            </div>

            <div className="review-card">
              <div className="review-facts">
                <span>共 {selectedCount} 道题</span>
                <span>每题 {points} 分</span>
                <span>满分 100 分</span>
              </div>

              <p className="review-rule">
                单选题点选后自动判分；填空题提交后显示参考答案，由你自己判断对错。
                答题过程中可以随时回到上一题修改选择。
              </p>

              <div className="review-count">
                <span className="review-count-label">抽题数量</span>
                <div className="review-count-options" role="group" aria-label="抽题数量">
                  {QUESTION_COUNTS.map((count) => {
                    const disabled = count > questions.length
                    return (
                      <button
                        key={count}
                        type="button"
                        className={count === selectedCount ? 'review-count-option is-selected' : 'review-count-option'}
                        aria-pressed={count === selectedCount}
                        disabled={disabled}
                        title={disabled ? `题库共 ${questions.length} 道，不足 ${count} 道` : `抽 ${count} 道题，每题 ${100 / count} 分`}
                        onClick={() => setSelectedCount(count)}
                      >
                        <strong>{count} 题</strong>
                        <span>每题 {100 / count} 分</span>
                      </button>
                    )
                  })}
                </div>
                <p className="review-hint">
                  当前题库共 {questions.length} 道（单选 {singleCount} 道、填空 {shortCount} 道）；超过题库总数的选项不可选。
                </p>
              </div>

              <div className="review-start-row">
                <button type="button" className="primary review-start-btn" onClick={startPractice}>
                  开始练习
                </button>
              </div>
            </div>
          </>
        )}

        {!loading && !error && stage === 'quiz' && currentQuestion && (
          <>
            <div className="review-quiz-head">
              <span className="review-quiz-kicker">复习测试</span>
              <span className="review-progress-text">
                第 {questionIndex + 1} / {sample.length} 题 · 每题 {points} 分
              </span>
            </div>
            <div
              className="review-progress-track"
              role="progressbar"
              aria-label="答题进度"
              aria-valuemin={1}
              aria-valuemax={sample.length}
              aria-valuenow={questionIndex + 1}
            >
              <div
                className="review-progress-fill"
                style={{ width: `${((questionIndex + 1) / sample.length) * 100}%` }}
              />
            </div>

            <article className="review-question-card" data-type={currentQuestion.questionType}>
              <p className="review-question-meta">
                {currentQuestion.questionType === 'single_choice' ? '单选题 · 自动判分' : '填空题 · 提交后自评'}
              </p>
              <h3 className="review-stem">{currentQuestion.stem}</h3>

              {currentQuestion.questionType === 'single_choice' && (
                <div className="review-options" role="group" aria-label="选项">
                  {(currentQuestion.options ?? []).map((option) => {
                    const selected = currentAnswer.selectedKey === option.key
                    return (
                      <button
                        key={option.key}
                        type="button"
                        className={selected ? 'review-option is-selected' : 'review-option'}
                        aria-pressed={selected}
                        onClick={() => patchAnswer(currentQuestion.id, { selectedKey: option.key })}
                      >
                        <span className="review-option-key">{option.key}</span>
                        <span className="review-option-text">{option.text}</span>
                      </button>
                    )
                  })}
                </div>
              )}

              {currentQuestion.questionType === 'short_answer' && (
                currentAnswer.submitted ? (
                  <div className="review-short">
                    <div className="review-compare">
                      <div>
                        <span className="review-reference-label">你的作答</span>
                        <p className="review-reference-text">{currentAnswer.text.trim() || '未作答'}</p>
                      </div>
                      <div>
                        <span className="review-reference-label">参考答案</span>
                        <p className="review-reference-text">
                          {referenceAnswer(currentQuestion) || '该题没有参考答案，请按自己的理解判断。'}
                        </p>
                      </div>
                    </div>
                    <div className="review-self">
                      <span className="review-self-label">你的作答算对了吗？</span>
                      <button
                        type="button"
                        data-self="right"
                        className={currentAnswer.selfCorrect === true ? 'review-self-btn is-right is-active' : 'review-self-btn'}
                        onClick={() => patchAnswer(currentQuestion.id, { selfCorrect: true })}
                      >
                        答对了
                      </button>
                      <button
                        type="button"
                        data-self="wrong"
                        className={currentAnswer.selfCorrect === false ? 'review-self-btn is-wrong is-active' : 'review-self-btn'}
                        onClick={() => patchAnswer(currentQuestion.id, { selfCorrect: false })}
                      >
                        答错了
                      </button>
                    </div>
                  </div>
                ) : (
                  <div className="review-short">
                    <textarea
                      className="review-short-input"
                      value={currentAnswer.text}
                      placeholder="在这里写下你的答案…"
                      onChange={(event) => patchAnswer(currentQuestion.id, { text: event.target.value })}
                    />
                    <div className="review-short-actions">
                      <button
                        type="button"
                        className="primary review-short-submit"
                        onClick={() => patchAnswer(currentQuestion.id, { submitted: true })}
                      >
                        提交并看参考答案
                      </button>
                    </div>
                  </div>
                )
              )}
            </article>

            <div className="review-actions">
              <button type="button" className="review-prev-btn" onClick={goPrev} disabled={questionIndex === 0}>
                上一题
              </button>
              <div className="review-actions-right">
                {advanceHint && <span className="review-advance-hint">{advanceHint}</span>}
                <button
                  type="button"
                  className="primary review-next-btn"
                  onClick={goNext}
                  disabled={!canAdvance}
                >
                  {questionIndex === sample.length - 1 ? '完成并查看结果' : '下一题'}
                </button>
              </div>
            </div>
          </>
        )}

        {!loading && !error && stage === 'result' && (
          <>
            <div className="page-title-row">
              <div>
                <h2>练习结果</h2>
                <p>本次共 {sample.length} 道题，每题 {points} 分。成绩不保存，刷新即清空。</p>
              </div>
              <div className="review-result-actions">
                <button
                  type="button"
                  className="review-filter-btn"
                  onClick={() => setOnlyWrong((value) => !value)}
                >
                  {onlyWrong ? '显示全部题目' : '查看错题'}
                </button>
                <button type="button" className="primary review-restart-btn" onClick={restart}>
                  再练一次
                </button>
              </div>
            </div>

            <div className="review-score-card">
              <span className="review-score-number">{score}</span>
              <span className="review-score-unit">分</span>
              <span className="review-score-meta">
                答对 {correctCount} / {sample.length} 题 · 满分 100
              </span>
            </div>

            {onlyWrong && visibleResults.length === 0 && (
              <p className="review-hint review-result-empty">全部答对了，没有错题。</p>
            )}

            <div className="review-result-list">
              {visibleResults.map((row) => (
                <article
                  key={row.question.id}
                  className={row.correct ? 'review-result-item is-right' : 'review-result-item is-wrong'}
                >
                  <header className="review-result-head">
                    <span className="review-result-index">
                      第 {row.index + 1} 题 · {row.question.questionType === 'single_choice' ? '单选题' : '填空题'}
                    </span>
                    <span className={row.correct ? 'review-result-mark is-right' : 'review-result-mark is-wrong'}>
                      {row.correct ? `✓ 答对 +${points}` : '✗ 答错 +0'}
                    </span>
                  </header>
                  <p className="review-result-stem">{row.question.stem}</p>
                  <div className="review-result-row">
                    <span className="review-result-label">你的作答</span>
                    <span className="review-result-value">{userAnswerText(row.question, row.answer)}</span>
                  </div>
                  <div className="review-result-row">
                    <span className="review-result-label">
                      {row.question.questionType === 'single_choice' ? '正确答案' : '参考答案'}
                    </span>
                    <span className="review-result-value">{correctAnswerText(row.question)}</span>
                  </div>
                </article>
              ))}
            </div>
          </>
        )}
      </section>
    </main>
  )
}
