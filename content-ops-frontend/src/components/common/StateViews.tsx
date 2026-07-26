/**
 * 通用加载/错误状态组件。
 * 各页面可复用，统一 Loading 和 Error 的视觉表现。
 */

interface LoadingProps {
  text?: string
}

export function LoadingView({ text = '加载中...' }: LoadingProps) {
  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        padding: '4rem 2rem',
        gap: '1rem',
      }}
    >
      <div
        style={{
          width: 32,
          height: 32,
          border: '3px solid #e5e6eb',
          borderTopColor: '#165dff',
          borderRadius: '50%',
          animation: 'spin 0.8s linear infinite',
        }}
      />
      <span style={{ color: '#86909c', fontSize: 14 }}>{text}</span>
      <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
    </div>
  )
}

interface ErrorProps {
  message?: string
  onRetry?: () => void
}

export function ErrorView({ message = '加载失败', onRetry }: ErrorProps) {
  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        padding: '4rem 2rem',
        gap: '1rem',
      }}
    >
      <svg width="40" height="40" fill="none" stroke="#f53f3f" viewBox="0 0 24 24" strokeWidth="1.8">
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          d="M12 9v3.75m9-.75a9 9 0 1 1-18 0 9 9 0 0 1 18 0Zm-9 3.75h.008v.008H12v-.008Z"
        />
      </svg>
      <span style={{ color: '#f53f3f', fontSize: 14, textAlign: 'center' }}>{message}</span>
      {onRetry && (
        <button
          onClick={onRetry}
          style={{
            padding: '6px 16px',
            border: '1px solid #165dff',
            borderRadius: 6,
            background: '#fff',
            color: '#165dff',
            fontSize: 13,
            cursor: 'pointer',
            fontWeight: 500,
          }}
        >
          重试
        </button>
      )}
    </div>
  )
}

/** 空状态 */
interface EmptyViewProps {
  text?: string
  title?: string
  description?: string
  actionLabel?: string
  actionLink?: string
}

export function EmptyView({ text, title, description, actionLabel, actionLink }: EmptyViewProps) {
  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        padding: '4rem 2rem',
        gap: '0.75rem',
      }}
    >
      <svg width="48" height="48" fill="none" stroke="#C9CDD4" viewBox="0 0 24 24" strokeWidth="1.5">
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          d="M20.25 7.5l-.625 10.632a2.25 2.25 0 0 1-2.247 2.118H6.622a2.25 2.25 0 0 1-2.247-2.118L3.75 7.5M10 11.25h4M3.375 7.5h17.25c.621 0 1.125-.504 1.125-1.125v-1.5c0-.621-.504-1.125-1.125-1.125H3.375c-.621 0-1.125.504-1.125 1.125v1.5c0 .621.504 1.125 1.125 1.125Z"
        />
      </svg>
      {title && (
        <span style={{ color: '#1D2129', fontSize: 16, fontWeight: 600 }}>{title}</span>
      )}
      {description && (
        <span style={{ color: '#86909C', fontSize: 14, textAlign: 'center', maxWidth: 320 }}>
          {description}
        </span>
      )}
      {!title && !description && text && (
        <span style={{ color: '#86909C', fontSize: 14 }}>{text}</span>
      )}
      {actionLabel && actionLink && (
        <a
          href={actionLink}
          style={{
            marginTop: '0.5rem',
            padding: '8px 20px',
            background: '#165DFF',
            color: '#fff',
            borderRadius: 8,
            fontSize: 14,
            fontWeight: 500,
            textDecoration: 'none',
            display: 'inline-block',
          }}
        >
          {actionLabel}
        </a>
      )}
    </div>
  )
}
