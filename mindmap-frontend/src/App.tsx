import { Suspense, lazy, useCallback, useEffect, useState } from 'react'
import { Sidebar, type AppView } from './components/layout/Sidebar'
import { LoginPage } from './pages/LoginPage'
import { createDocument, deleteDocument, listDocuments, renameDocument } from './api/documents'
import { useAuthStore } from './stores/authStore'
import type { DocumentSummaryResponse } from './types/document'

// 路由级懒加载：每个界面单独一个 chunk，首屏只下载当前界面。
// 页面都是命名导出，React.lazy 只认 default，所以要包一层。
// 登录页保持同步引入：它是未登录用户的第一屏，懒加载只会多一次等待。
const HomePage = lazy(() => import('./pages/HomePage').then((m) => ({ default: m.HomePage })))
const NotesPage = lazy(() => import('./pages/NotesPage').then((m) => ({ default: m.NotesPage })))
const QuestionRecordsPage = lazy(() =>
  import('./pages/QuestionRecordsPage').then((m) => ({ default: m.QuestionRecordsPage }))
)
const ReviewTestPage = lazy(() =>
  import('./pages/ReviewTestPage').then((m) => ({ default: m.ReviewTestPage }))
)
const SettingsPage = lazy(() => import('./pages/SettingsPage').then((m) => ({ default: m.SettingsPage })))
const AiAssistantPage = lazy(() =>
  import('./pages/AiAssistantPage').then((m) => ({ default: m.AiAssistantPage }))
)

const DOCUMENT_ID_KEY = 'aimindmap.currentDocumentId'

const VIEW_PATHS: Record<AppView, string> = {
  home: '/index',
  notes: '/notes',
  'question-records': '/questions',
  review: '/review',
  'ai-assistant': '/ai',
  settings: '/settings'
}

function normalizePath(pathname: string): string {
  return pathname.length > 1 && pathname.endsWith('/') ? pathname.slice(0, -1) : pathname
}

function getViewFromPath(pathname: string): AppView {
  const path = normalizePath(pathname)
  const entry = Object.entries(VIEW_PATHS).find(([, viewPath]) => viewPath === path)
  return entry ? (entry[0] as AppView) : 'home'
}

function isKnownPath(pathname: string): boolean {
  return Object.values(VIEW_PATHS).includes(normalizePath(pathname))
}

// 懒加载界面的兜底：一句话 + 一个安静的点，不用骨架屏（骨架会晃，反而更慢）
function ViewLoading() {
  return (
    <div className="view-loading" role="status" aria-live="polite">
      <span className="view-loading-dot" aria-hidden="true" />
      <p>正在加载界面…</p>
    </div>
  )
}

export default function App() {
  const { initialized, user, bootstrap, logout } = useAuthStore()
  const [activeView, setActiveView] = useState<AppView>(() => getViewFromPath(window.location.pathname))
  const [currentDocumentId, setCurrentDocumentId] = useState<string | null>(() => {
    return window.localStorage.getItem(DOCUMENT_ID_KEY)
  })
  const [documents, setDocuments] = useState<DocumentSummaryResponse[]>([])
  const [documentsLoading, setDocumentsLoading] = useState(false)
  const [documentsError, setDocumentsError] = useState('')
  const [homeQuestionsOpen, setHomeQuestionsOpen] = useState(false)
  // AI 助手页原本一直挂载（切走也保留会话状态）。改成懒加载后，第一次进入才挂载：
  // 没访问过就没有状态需要保留，访问过之后仍然常驻，切换不丢消息。
  const [aiVisited, setAiVisited] = useState(activeView === 'ai-assistant')

  const refreshDocuments = useCallback(async () => {
    setDocumentsLoading(true)
    try {
      setDocuments(await listDocuments())
      setDocumentsError('')
    } catch (reason) {
      setDocumentsError(reason instanceof Error ? reason.message : '获取笔记列表失败')
    } finally {
      setDocumentsLoading(false)
    }
  }, [])

  useEffect(() => {
    void bootstrap()
  }, [bootstrap])

  useEffect(() => {
    const handleUnauthorized = () => logout()
    window.addEventListener('aimindmap:unauthorized', handleUnauthorized)
    return () => window.removeEventListener('aimindmap:unauthorized', handleUnauthorized)
  }, [logout])

  // 打开未知地址时替换到首页，避免页面状态和 URL 不一致
  useEffect(() => {
    if (!isKnownPath(window.location.pathname)) {
      window.history.replaceState(null, '', VIEW_PATHS.home)
    }
  }, [])

  useEffect(() => {
    const handlePopState = () => {
      setActiveView(getViewFromPath(window.location.pathname))
    }
    window.addEventListener('popstate', handlePopState)
    return () => window.removeEventListener('popstate', handlePopState)
  }, [])

  const navigateView = useCallback((view: AppView) => {
    const nextPath = VIEW_PATHS[view]
    setActiveView(view)
    if (normalizePath(window.location.pathname) !== nextPath) {
      window.history.pushState(null, '', nextPath)
    }
  }, [])

  useEffect(() => {
    if (activeView === 'ai-assistant') {
      setAiVisited(true)
    }
  }, [activeView])

  useEffect(() => {
    if (user) {
      void refreshDocuments()
    }
  }, [refreshDocuments, user])

  const openDocument = useCallback((id: string, openQuestions = false) => {
    window.localStorage.setItem(DOCUMENT_ID_KEY, id)
    setCurrentDocumentId(id)
    setHomeQuestionsOpen(openQuestions)
    navigateView('home')
  }, [])

  const renameDocumentById = useCallback(async (id: string, title: string) => {
    await renameDocument(id, title)
    await refreshDocuments()
  }, [refreshDocuments])

  const deleteDocumentById = useCallback(async (id: string) => {
    await deleteDocument(id)
    if (currentDocumentId === id) {
      window.localStorage.removeItem(DOCUMENT_ID_KEY)
      setCurrentDocumentId(null)
    }
    await refreshDocuments()
  }, [refreshDocuments, currentDocumentId])

  /**
   * 批量删除：逐条删，全部处理完再统一刷新一次。
   * 单条失败不中断整批 —— 否则用户不知道到底删掉了哪几篇；
   * 最后把失败数量抛给页面提示（列表已经刷新过，不会停留在旧状态）。
   */
  const deleteDocumentsById = useCallback(async (ids: string[]) => {
    if (ids.length === 0) return
    const failed: string[] = []
    for (const id of ids) {
      try {
        await deleteDocument(id)
      } catch {
        failed.push(id)
      }
    }
    if (currentDocumentId && ids.includes(currentDocumentId) && !failed.includes(currentDocumentId)) {
      window.localStorage.removeItem(DOCUMENT_ID_KEY)
      setCurrentDocumentId(null)
    }
    await refreshDocuments()
    if (failed.length > 0) {
      throw new Error(`有 ${failed.length} 篇删除失败，列表已刷新，请重试`)
    }
  }, [refreshDocuments, currentDocumentId])

  const createAndOpenDocument = useCallback(async () => {
    const document = await createDocument('未命名文档')
    window.localStorage.setItem(DOCUMENT_ID_KEY, document.id)
    setCurrentDocumentId(document.id)
    setHomeQuestionsOpen(false)
    navigateView('home')
    await refreshDocuments()
  }, [refreshDocuments])

  if (!initialized) {
    return (
      <main className="auth-loading">
        <div className="auth-loading-card">
          <div className="auth-logo">AI</div>
          <p>正在检查登录状态...</p>
        </div>
      </main>
    )
  }

  if (!user) {
    return <LoginPage />
  }

  return (
    <div className={activeView === 'home' && homeQuestionsOpen ? 'app-shell with-questions' : 'app-shell'}>
      <Sidebar
        activeView={activeView}
        onNavigate={navigateView}
        user={user}
      />

      {activeView === 'home' && (
        <Suspense fallback={<ViewLoading />}>
          <HomePage
            key={currentDocumentId ?? 'empty-document'}
            initialDocumentId={currentDocumentId}
            initialShowQuestions={homeQuestionsOpen}
            onCurrentDocumentChange={(id) => {
              window.localStorage.setItem(DOCUMENT_ID_KEY, id)
              setCurrentDocumentId(id)
            }}
            onDocumentsChanged={() => void refreshDocuments()}
            onQuestionPanelVisibilityChange={setHomeQuestionsOpen}
          />
        </Suspense>
      )}

      {activeView === 'notes' && (
        <Suspense fallback={<ViewLoading />}>
          <NotesPage
            documents={documents}
            loading={documentsLoading}
            error={documentsError}
            currentDocumentId={currentDocumentId}
            onOpenDocument={(id) => openDocument(id)}
            onCreateDocument={() => void createAndOpenDocument()}
            onRenameDocument={(id, title) => renameDocumentById(id, title)}
            onDeleteDocument={(id) => deleteDocumentById(id)}
            onDeleteDocuments={(ids) => deleteDocumentsById(ids)}
            onDocumentsChanged={() => void refreshDocuments()}
          />
        </Suspense>
      )}

      {activeView === 'question-records' && (
        <Suspense fallback={<ViewLoading />}>
          <QuestionRecordsPage onOpenDocument={(id) => openDocument(id, true)} />
        </Suspense>
      )}

      {activeView === 'review' && (
        <Suspense fallback={<ViewLoading />}>
          <ReviewTestPage />
        </Suspense>
      )}

      <div className={activeView === 'ai-assistant' ? 'persisted-view active' : 'persisted-view hidden'}>
        {aiVisited && (
          <Suspense fallback={<ViewLoading />}>
            <AiAssistantPage />
          </Suspense>
        )}
      </div>

      {activeView === 'settings' && (
        <Suspense fallback={<ViewLoading />}>
          <SettingsPage />
        </Suspense>
      )}
    </div>
  )
}
