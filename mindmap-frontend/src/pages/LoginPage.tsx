import { FormEvent, useState } from 'react'
import { useAuthStore } from '../stores/authStore'

type Mode = 'login' | 'register'

export function LoginPage() {
  const [mode, setMode] = useState<Mode>('login')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [major, setMajor] = useState('')
  const { submitting, error, login, register, clearError } = useAuthStore()

  const switchMode = (next: Mode) => {
    setMode(next)
    clearError()
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (mode === 'login') {
      await login({ username: username.trim(), password })
      return
    }
    await register({
      username: username.trim(),
      password,
      confirmPassword,
      major: major.trim()
    })
  }

  return (
    <main className="auth-page">
      <section className="auth-card">
        <div className="auth-brand">
          <div className="auth-logo">AI</div>
          <div>
            <h1>智能学习笔记</h1>
            <p>登录后继续管理你的 Markdown、思维导图和题目</p>
          </div>
        </div>

        <div className="auth-tabs" role="tablist" aria-label="登录或注册">
          <button
            type="button"
            className={mode === 'login' ? 'active' : ''}
            onClick={() => switchMode('login')}
          >
            登录
          </button>
          <button
            type="button"
            className={mode === 'register' ? 'active' : ''}
            onClick={() => switchMode('register')}
          >
            注册
          </button>
        </div>

        <form className="auth-form" onSubmit={handleSubmit}>
          <label>
            <span>账号</span>
            <input
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              placeholder="3-64 个字符"
              autoComplete="username"
              required
              minLength={3}
              maxLength={64}
            />
          </label>

          <label>
            <span>密码</span>
            <input
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              placeholder="至少 6 位"
              autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
              required
              minLength={6}
              maxLength={64}
            />
          </label>

          {mode === 'register' && (
            <>
              <label>
                <span>确认密码</span>
                <input
                  type="password"
                  value={confirmPassword}
                  onChange={(event) => setConfirmPassword(event.target.value)}
                  autoComplete="new-password"
                  required
                  minLength={6}
                  maxLength={64}
                />
              </label>
              <label>
                <span>专业</span>
                <input
                  value={major}
                  onChange={(event) => setMajor(event.target.value)}
                  placeholder="例如：计算机科学与技术"
                  required
                  maxLength={128}
                />
              </label>
            </>
          )}

          {error && <div className="auth-error" role="alert">{error}</div>}

          <button className="primary auth-submit" type="submit" disabled={submitting}>
            {submitting ? '请稍候...' : mode === 'login' ? '登录' : '注册并登录'}
          </button>
        </form>

        <p className="auth-note">
          {mode === 'login'
            ? '还没有账号？点击上方“注册”创建。'
            : '专业信息当前只用于账号资料；AI 知识助手将在登录体系验收后再开发。'}
        </p>
      </section>
    </main>
  )
}
