import { NavLink } from 'react-router-dom'
import {
  LayoutDashboard, Workflow, MessageSquareText,
  Bot, History, Sparkles
} from 'lucide-react'
import { cn } from '@/utils/cn'

const navItems = [
  { to: '/', icon: LayoutDashboard, label: '仪表盘' },
  { to: '/workflow', icon: Workflow, label: '工作流' },
  { to: '/discussion', icon: MessageSquareText, label: '讨论选题' },
  { to: '/agents', icon: Bot, label: 'Agent 面板' },
  { to: '/history', icon: History, label: '历史记录' },
]

export function Sidebar() {
  return (
    <aside className="fixed left-0 top-0 z-30 flex h-screen w-[var(--sidebar-width)] flex-col border-r border-gray-200 bg-white">
      <div className="flex items-center gap-2 border-b border-gray-100 px-6 py-4">
        <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-brand-600">
          <Sparkles className="h-5 w-5 text-white" />
        </div>
        <div>
          <h1 className="text-sm font-bold text-gray-900">Content Ops</h1>
          <p className="text-xs text-gray-500">AI 内容运营平台</p>
        </div>
      </div>
      <nav className="flex-1 space-y-1 px-3 py-4">
        {navItems.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            end={item.to === '/'}
            className={({ isActive }) =>
              cn(
                'flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-colors',
                isActive
                  ? 'bg-brand-50 text-brand-700'
                  : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900'
              )
            }
          >
            <item.icon className="h-[18px] w-[18px]" />
            {item.label}
          </NavLink>
        ))}
      </nav>
      <div className="border-t border-gray-100 px-6 py-4">
        <p className="text-xs text-gray-400">v1.0.0 · 2026</p>
      </div>
    </aside>
  )
}
