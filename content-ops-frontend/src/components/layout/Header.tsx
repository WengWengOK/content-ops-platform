import { useState } from 'react'
import { Menu, Github, Bell } from 'lucide-react'
import { Button } from '@/components/ui/Button'

interface HeaderProps {
  onToggleSidebar?: () => void
}

export function Header({ onToggleSidebar }: HeaderProps) {
  const [showNotif, setShowNotif] = useState(false)

  return (
    <header className="sticky top-0 z-20 flex h-14 items-center justify-between border-b border-gray-200 bg-white/80 px-6 backdrop-blur">
      <div className="flex items-center gap-3">
        {onToggleSidebar && (
          <button onClick={onToggleSidebar} className="rounded-lg p-1.5 hover:bg-gray-100 lg:hidden">
            <Menu className="h-5 w-5 text-gray-600" />
          </button>
        )}
        <div className="hidden items-center gap-2 text-sm text-gray-500 lg:flex">
          <span>Content Ops Agent Platform</span>
        </div>
      </div>
      <div className="flex items-center gap-2">
        <button
          onClick={() => setShowNotif(!showNotif)}
          className="relative rounded-lg p-2 text-gray-500 hover:bg-gray-100 hover:text-gray-700"
        >
          <Bell className="h-[18px] w-[18px]" />
          <span className="absolute right-1.5 top-1.5 h-2 w-2 rounded-full bg-red-500"></span>
        </button>
        <a
          href="https://github.com/WengWengOK/content-ops-platform"
          target="_blank"
          rel="noopener noreferrer"
        >
          <Button variant="ghost" size="sm">
            <Github className="h-4 w-4" />
            GitHub
          </Button>
        </a>
        <div className="flex h-8 w-8 items-center justify-center rounded-full bg-brand-100 text-sm font-semibold text-brand-700">
          A
        </div>
      </div>
    </header>
  )
}
