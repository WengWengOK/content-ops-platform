import { Loader2 } from 'lucide-react'
import { cn } from '@/utils/cn'

interface LoadingProps {
  size?: 'sm' | 'md' | 'lg'
  text?: string
  className?: string
}

const sizeMap = { sm: 'h-4 w-4', md: 'h-6 w-6', lg: 'h-8 w-8' }
const textMap = { sm: 'text-xs', md: 'text-sm', lg: 'text-base' }

export function Loading({ size = 'md', text, className }: LoadingProps) {
  return (
    <div className={cn('flex items-center justify-center gap-2', className)}>
      <Loader2 className={cn('animate-spin text-brand-500', sizeMap[size])} />
      {text && <span className={cn('text-gray-500', textMap[size])}>{text}</span>}
    </div>
  )
}

export function FullPageLoading({ text = '加载中...' }: { text?: string }) {
  return (
    <div className="flex h-full min-h-[400px] items-center justify-center">
      <Loading size="lg" text={text} />
    </div>
  )
}
