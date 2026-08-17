import { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { BreadcrumbItem } from './Layout'
import { useAuth } from '@/store/auth'

interface HeaderProps {
  pageTitle?: string
  breadcrumbs?: BreadcrumbItem[]
  showBackButton?: boolean
  backHref?: string
  headerRight?: ReactNode
}

export function Header({ pageTitle, breadcrumbs, showBackButton, backHref, headerRight }: HeaderProps) {
  const { user, logout } = useAuth()

  return (
    <header
      className="sticky top-0 z-30 flex items-center justify-between h-16 px-6 border-b"
      style={{ background: '#FFFFFF', borderColor: '#E5E6EB' }}
    >
      {/* Left: Page Title or Breadcrumb */}
      <div className="flex items-center gap-2 text-sm">
        {showBackButton && backHref && (
          <Link
            to={backHref}
            className="flex items-center gap-1 hover:underline"
            style={{ color: '#86909C' }}
          >
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth="2">
              <path strokeLinecap="round" strokeLinejoin="round" d="M10.5 19.5 3 12m7.5 7.5 3-7.5m-3 7.5V4.5" />
            </svg>
          </Link>
        )}
        {breadcrumbs ? (
          <nav className="flex items-center gap-2 text-sm">
            {breadcrumbs.map((crumb, i) => (
              <span key={i} className="flex items-center gap-2">
                {crumb.href ? (
                  <Link to={crumb.href} className="hover:underline" style={{ color: '#86909C' }}>
                    {crumb.label}
                  </Link>
                ) : (
                  <span style={{ color: '#1D2129', fontWeight: 500 }}>{crumb.label}</span>
                )}
                {i < breadcrumbs.length - 1 && <span style={{ color: '#C9CDD4' }}>/</span>}
              </span>
            ))}
          </nav>
        ) : (
          pageTitle && (
            <h1 className="text-[16px] font-semibold" style={{ color: '#1D2129' }}>
              {pageTitle}
            </h1>
          )
        )}
      </div>

      {/* Right: Actions */}
      <div className="flex items-center gap-4">
        {headerRight}
        {/* Notification Bell */}
        <button
          className="relative p-2 rounded-xl transition-colors hover:bg-[#FFF0F5]"
          aria-label="通知"
        >
          <svg className="w-5 h-5" style={{ color: '#4E5969' }} fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth="1.8">
            <path strokeLinecap="round" strokeLinejoin="round" d="M14.857 17.082a23.848 23.848 0 0 0 5.454-1.31A8.967 8.967 0 0 1 18 9.75V9A6 6 0 0 0 6 9v.75a8.967 8.967 0 0 1-2.312 6.022c1.733.64 3.56 1.085 5.455 1.31m5.714 0a24.255 24.255 0 0 1-5.714 0m5.714 0a3 3 0 1 1-5.714 0" />
          </svg>
          <span className="absolute top-1.5 right-1.5 w-2 h-2 rounded-full" style={{ background: '#FF2D5E' }} />
        </button>

        {/* 用户信息 / 登录入口 */}
        {user ? (
          <div className="flex items-center gap-2.5 px-3 py-1.5 rounded-xl">
            <div
              className="w-8 h-8 rounded-full flex items-center justify-center text-white text-xs font-bold"
              style={{ background: 'linear-gradient(135deg, #FF2D5E, #FF5C8A)' }}
            >
              {user.username.slice(0, 1).toUpperCase()}
            </div>
            <span className="text-sm font-medium" style={{ color: '#4E5969' }}>
              {user.username}
            </span>
            <button
              onClick={logout}
              className="text-xs px-2 py-1 rounded-lg transition-colors hover:bg-[#FFF0F5]"
              style={{ color: '#F53F3F' }}
            >
              退出
            </button>
          </div>
        ) : (
          <Link
            to="/login"
            className="text-sm font-medium px-4 py-1.5 rounded-lg text-white"
            style={{ background: '#165DFF' }}
          >
            登录
          </Link>
        )}
      </div>
    </header>
  )
}
