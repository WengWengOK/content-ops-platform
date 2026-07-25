import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { cn } from '@/utils/cn'

interface MarkdownRendererProps {
  content: string
  className?: string
}

export function MarkdownRenderer({ content, className }: MarkdownRendererProps) {
  return (
    <div className={cn('prose prose-sm prose-gray max-w-none', className)}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          h1: ({ children }) => <h1 className="text-xl font-bold text-gray-900 mb-3">{children}</h1>,
          h2: ({ children }) => <h2 className="text-lg font-bold text-gray-900 mb-2 mt-4">{children}</h2>,
          h3: ({ children }) => <h3 className="text-base font-semibold text-gray-800 mb-2 mt-3">{children}</h3>,
          p: ({ children }) => <p className="text-sm text-gray-700 leading-relaxed mb-3">{children}</p>,
          ul: ({ children }) => <ul className="list-disc list-inside text-sm text-gray-700 mb-3 space-y-1">{children}</ul>,
          ol: ({ children }) => <ol className="list-decimal list-inside text-sm text-gray-700 mb-3 space-y-1">{children}</ol>,
          li: ({ children }) => <li className="leading-relaxed">{children}</li>,
          strong: ({ children }) => <strong className="font-semibold text-gray-900">{children}</strong>,
          em: ({ children }) => <em className="italic text-gray-600">{children}</em>,
          blockquote: ({ children }) => <blockquote className="border-l-4 border-brand-300 bg-brand-50 px-4 py-2 rounded-r text-sm text-gray-700 my-3">{children}</blockquote>,
          code: ({ children, className, ...props }: any) => {
            const isInline = !className
            return isInline ? (
              <code className="rounded bg-gray-100 px-1.5 py-0.5 text-xs text-brand-600 font-mono" {...props}>{children}</code>
            ) : (
              <pre className="bg-gray-900 text-gray-100 rounded-lg p-4 overflow-x-auto my-3">
                <code className="text-xs font-mono">{children}</code>
              </pre>
            )
          },
          a: ({ href, children }) => <a href={href} target="_blank" rel="noopener noreferrer" className="text-brand-600 hover:text-brand-700 underline">{children}</a>,
          table: ({ children }) => <table className="w-full border-collapse text-sm my-3">{children}</table>,
          th: ({ children }) => <th className="border border-gray-300 bg-gray-50 px-3 py-1.5 text-left font-semibold text-gray-700">{children}</th>,
          td: ({ children }) => <td className="border border-gray-300 px-3 py-1.5 text-gray-600">{children}</td>,
          hr: () => <hr className="border-gray-200 my-4" />,
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  )
}
