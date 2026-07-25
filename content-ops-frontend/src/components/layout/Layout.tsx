import type { ReactNode } from 'react'
import { Sidebar } from './Sidebar'
import { Header } from './Header'

interface LayoutProps {
  children: ReactNode
}

export function Layout({ children }: LayoutProps) {
  return (
    <div className="min-h-screen">
      <Sidebar />
      <div className="ml-[var(--sidebar-width)]">
        <Header />
        <main className="p-6">
          {children}
        </main>
      </div>
    </div>
  )
}
