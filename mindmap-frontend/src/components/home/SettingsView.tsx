import type { UserProfile } from '../../types/auth'

export function SettingsView({
  user,
  onLogout
}: {
  user: UserProfile
  onLogout: () => void
}) {
  return (
    <section className="page-view settings-page">
      <div className="page-title-row">
        <div>
          <h2>设置</h2>
          <p>当前版本的本地运行信息和安全说明。</p>
        </div>
      </div>

      <div className="settings-grid">
        <article className="settings-card settings-account-card">
          <h3>账号信息</h3>
          <div className="settings-account-row">
            <span>账号</span>
            <strong>{user.username}</strong>
          </div>
          <div className="settings-account-row">
            <span>专业</span>
            <strong>{user.major}</strong>
          </div>
          <button className="settings-logout-btn" type="button" onClick={onLogout}>
            退出登录
          </button>
        </article>
        <article className="settings-card">
          <h3>数据保存</h3>
          <p>笔记、思维导图和题目保存在本机 PostgreSQL 的 <code>aimindmap</code> 数据库中。</p>
        </article>
        <article className="settings-card">
          <h3>后端服务</h3>
          <p>本地后端地址：<code>http://localhost:8080</code></p>
        </article>
        <article className="settings-card">
          <h3>前端服务</h3>
          <p>开发页面运行在 Vite 端口 <code>5273</code>，接口通过开发服务器代理到后端。</p>
        </article>
        <article className="settings-card">
          <h3>AI 密钥</h3>
          <p>AI Key 只允许配置在后端环境变量 <code>AI_API_KEY</code> 中，前端页面不会保存或显示密钥。</p>
        </article>
        <article className="settings-card">
          <h3>题目说明</h3>
          <p>当前版本不提供题目手动编辑。若题目不符合预期，请使用“重新生成此题”。</p>
        </article>
      </div>
    </section>
  )
}
