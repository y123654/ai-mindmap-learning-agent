import type { UserProfile } from '../../types/auth'

export type AppView = 'home' | 'notes' | 'question-records' | 'ai-assistant' | 'settings'

interface SidebarProps {
  activeView: AppView
  onNavigate: (view: AppView) => void
  user: UserProfile
}

function HomeIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M3 10.5 12 3l9 7.5" />
      <path d="M5 9.5V21h14V9.5" />
      <path d="M9.5 21v-6h5v6" />
    </svg>
  )
}

function NotesIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="4" y="3" width="16" height="18" rx="2" />
      <path d="M8 8h8M8 12h8M8 16h5" />
    </svg>
  )
}

function QuestionIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <circle cx="12" cy="12" r="9" />
      <path d="M9.6 9.2c.3-1.3 1.3-2 2.5-2 1.4 0 2.4.9 2.4 2.1 0 1-.5 1.6-1.4 2.1-.8.4-1.1.8-1.1 1.6" />
      <circle cx="12" cy="17" r="0.6" fill="currentColor" stroke="none" />
    </svg>
  )
}

function AssistantIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M12 3a5 5 0 0 0-5 5v1a5 5 0 0 0 10 0V8a5 5 0 0 0-5-5Z" />
      <path d="M7 11c-1.7 0-3 1.6-3 3.5S5.3 18 7 18" />
      <path d="M17 11c1.7 0 3 1.6 3 3.5S18.7 18 17 18" />
      <path d="M10 21h4M12 18v3" />
    </svg>
  )
}

function SettingsIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <circle cx="12" cy="12" r="3" />
      <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
    </svg>
  )
}

/**
 * 左侧导航。
 *
 * 2026-10-29 起不再渲染「我的笔记」滚动列表：
 * 笔记的浏览 / 重命名 / 删除 / 批量删除统一放到「笔记记录」页，
 * 侧栏只保留固定导航 + 底部用户信息，避免同一份数据在侧栏和内容区各画一遍。
 */
export function Sidebar({ activeView, onNavigate, user }: SidebarProps) {
  return (
    <aside className="app-sidebar">
      <div className="brand-block">
        <div className="brand-logo">AI</div>
        <div>
          <strong>智能学习笔记</strong>
          <span>Mindmap Notes</span>
        </div>
      </div>

      <nav className="sidebar-nav" aria-label="主导航">
        <button className={activeView === 'home' ? 'active' : ''} onClick={() => onNavigate('home')}>
          <span className="nav-icon"><HomeIcon /></span>
          首页
        </button>
        <button className={activeView === 'notes' ? 'active' : ''} onClick={() => onNavigate('notes')}>
          <span className="nav-icon"><NotesIcon /></span>
          笔记记录
        </button>
        <button
          className={activeView === 'question-records' ? 'active' : ''}
          onClick={() => onNavigate('question-records')}
        >
          <span className="nav-icon"><QuestionIcon /></span>
          题目记录
        </button>
        <button
          className={activeView === 'ai-assistant' ? 'active' : ''}
          onClick={() => onNavigate('ai-assistant')}
        >
          <span className="nav-icon"><AssistantIcon /></span>
          AI知识助手
        </button>
        <button className={activeView === 'settings' ? 'active' : ''} onClick={() => onNavigate('settings')}>
          <span className="nav-icon"><SettingsIcon /></span>
          设置
        </button>
      </nav>

      {/* 撑开中间留白，把用户信息压到底部（原来这块高度由笔记列表占着） */}
      <div className="sidebar-spacer" />

      <div className="sidebar-current-user" title={`${user.username}｜${user.major}`}>
        <span className="sidebar-user-avatar">{user.username.slice(0, 1).toUpperCase()}</span>
        <span className="sidebar-user-meta">
          <strong>{user.username}</strong>
          <em>{user.major}</em>
        </span>
      </div>
    </aside>
  )
}
