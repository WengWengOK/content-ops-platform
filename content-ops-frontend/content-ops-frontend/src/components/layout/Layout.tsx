import { ReactNode } from 'react'
import { Sidebar } from './Sidebar'
import { Header } from './Header'

export interface BreadcrumbItem {
  label: string
  href?: string
}

export interface LayoutProps {
  children: ReactNode
  pageTitle?: string
  breadcrumbs?: BreadcrumbItem[]
  activeNav?: string
  showBackButton?: boolean
  backHref?: string
  headerRight?: ReactNode
}

export function Layout({
  children,
  pageTitle,
  breadcrumbs,
  activeNav,
  showBackButton,
  backHref,
  headerRight,
}: LayoutProps) {
  return (
    <div
      className="flex min-h-screen w-full overflow-hidden"
      style={{ fontFamily: "'Inter', 'PingFang SC', 'Microsoft YaHei', sans-serif" }}
    >
      <Sidebar activeNav={activeNav} />

      <div className="flex-1 flex flex-col ml-[260px] min-h-screen">
        <Header
          pageTitle={pageTitle}
          breadcrumbs={breadcrumbs}
          showBackButton={showBackButton}
          backHref={backHref}
          headerRight={headerRight}
        />

        <main className="flex-1 p-6" style={{ background: '#F7F8FA' }}>
          <div className="mx-auto" style={{ maxWidth: 1200 }}>
            {children}
          </div>
        </main>
      </div>
    </div>
  )
}
