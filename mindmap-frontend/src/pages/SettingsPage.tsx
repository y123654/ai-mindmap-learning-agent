import { SettingsView } from '../components/home/SettingsView'
import { useAuthStore } from '../stores/authStore'

export function SettingsPage() {
  const user = useAuthStore((state) => state.user)
  const logout = useAuthStore((state) => state.logout)

  return (
    <main className="page-workspace">
      {user && <SettingsView user={user} onLogout={logout} />}
    </main>
  )
}
