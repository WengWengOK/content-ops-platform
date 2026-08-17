import { useState } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import { useAuth } from '@/store/auth'

type Mode = 'login' | 'register'

export function LoginPage() {
  const [mode, setMode] = useState<Mode>('login')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const navigate = useNavigate()
  const { token, login, register } = useAuth()

  if (token) {
    return <Navigate to="/" replace />
  }

  const handleSubmit = async () => {
    setSubmitting(true)
    setError('')
    try {
      if (mode === 'login') {
        await login({ username, password })
      } else {
        await register({ username, password })
        await login({ username, password })
      }
      navigate('/')
    } catch (err: any) {
      setError(err?.message || '操作失败，请重试')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div
      className="min-h-screen flex items-center justify-center px-4"
      style={{ background: 'linear-gradient(135deg, #FFF0F5 0%, #E8F3FF 100%)' }}
    >
      <div className="w-full max-w-md bg-white rounded-2xl shadow-lg p-8">
        <h1 className="text-xl font-semibold text-center" style={{ color: '#1D2129' }}>
          Content Ops 平台
        </h1>
        <p className="text-sm text-center mt-1 mb-6" style={{ color: '#86909C' }}>
          {mode === 'login' ? '登录后管理你的内容工作流' : '注册账号开启内容自动化'}
        </p>

        {/* 登录 / 注册切换 */}
        <div className="flex rounded-lg p-1 mb-6" style={{ background: '#F2F3F5' }}>
          {(['login', 'register'] as Mode[]).map((m) => (
            <button
              key={m}
              onClick={() => {
                setMode(m)
                setError('')
              }}
              className="flex-1 py-2 rounded-md text-sm font-medium transition-colors"
              style={
                mode === m
                  ? { background: '#FFFFFF', color: '#165DFF', boxShadow: '0 1px 4px rgba(0,0,0,0.08)' }
                  : { color: '#86909C' }
              }
            >
              {m === 'login' ? '登录' : '注册'}
            </button>
          ))}
        </div>

        <div className="space-y-4">
          <div>
            <label className="block text-sm mb-1" style={{ color: '#4E5969' }}>
              用户名
            </label>
            <input
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              placeholder="3-32 位字母/数字/下划线"
              className="w-full px-3 py-2 rounded-lg border text-sm outline-none"
              style={{ borderColor: '#E5E6EB', color: '#1D2129' }}
            />
          </div>
          <div>
            <label className="block text-sm mb-1" style={{ color: '#4E5969' }}>
              密码
            </label>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="8-64 位"
              className="w-full px-3 py-2 rounded-lg border text-sm outline-none"
              style={{ borderColor: '#E5E6EB', color: '#1D2129' }}
            />
          </div>
        </div>

        {error && (
          <div
            className="mt-4 px-3 py-2 rounded-lg text-sm"
            style={{ background: '#FFF2F0', color: '#F53F3F' }}
          >
            {error}
          </div>
        )}

        <button
          onClick={handleSubmit}
          disabled={submitting || !username.trim() || !password}
          className="w-full mt-6 py-2.5 rounded-lg text-sm font-medium text-white transition-opacity disabled:opacity-50"
          style={{ background: '#165DFF' }}
        >
          {submitting ? '请稍候...' : mode === 'login' ? '登录' : '注册并登录'}
        </button>
      </div>
    </div>
  )
}
