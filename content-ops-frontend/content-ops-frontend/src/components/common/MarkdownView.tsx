import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

/**
 * Markdown 渲染组件：Agent 输出 / 正文 / 实时产出统一可视化。
 * 支持 GFM（表格、删除线、任务列表），基础排版样式与卡片风格一致。
 */
export function MarkdownView({ content, className }: { content: string; className?: string }) {
  return (
    <div
      className={className ?? ''}
      style={{ lineHeight: 1.8, wordBreak: 'break-word' }}
    >
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          h1: (p) => <h1 {...p} style={{ fontSize: 18, fontWeight: 700, color: '#1D2129', margin: '12px 0 8px' }} />,
          h2: (p) => <h2 {...p} style={{ fontSize: 16, fontWeight: 700, color: '#1D2129', margin: '12px 0 8px' }} />,
          h3: (p) => <h3 {...p} style={{ fontSize: 14, fontWeight: 700, color: '#1D2129', margin: '10px 0 6px' }} />,
          p: (p) => <p {...p} style={{ margin: '6px 0' }} />,
          strong: (p) => <strong {...p} style={{ color: '#1D2129', fontWeight: 600 }} />,
          ul: (p) => <ul {...p} style={{ paddingLeft: 20, margin: '6px 0' }} />,
          ol: (p) => <ol {...p} style={{ paddingLeft: 20, margin: '6px 0' }} />,
          li: (p) => <li {...p} style={{ margin: '3px 0' }} />,
          a: (p) => (
            <a {...p} target="_blank" rel="noreferrer" style={{ color: '#165DFF', textDecoration: 'underline' }} />
          ),
          code: (p) => (
            <code
              {...p}
              style={{
                background: '#F2F3F5',
                color: '#4E5969',
                padding: '1px 5px',
                borderRadius: 4,
                fontSize: '0.9em',
                fontFamily: 'Consolas, Menlo, monospace',
              }}
            />
          ),
          pre: (p) => (
            <pre
              {...p}
              style={{
                background: '#F7F8FA',
                padding: 12,
                borderRadius: 8,
                overflowX: 'auto',
                fontSize: 13,
                lineHeight: 1.6,
              }}
            />
          ),
          table: (p) => (
            <table {...p} style={{ borderCollapse: 'collapse', margin: '8px 0', width: '100%' }} />
          ),
          th: (p) => <th {...p} style={{ border: '1px solid #E5E6EB', padding: '6px 10px', background: '#F7F8FA', textAlign: 'left' }} />,
          td: (p) => <td {...p} style={{ border: '1px solid #E5E6EB', padding: '6px 10px' }} />,
          blockquote: (p) => (
            <blockquote {...p} style={{ borderLeft: '3px solid #B5CFFF', margin: '8px 0', padding: '4px 12px', color: '#86909C', background: '#F7FAFF' }} />
          ),
        }}
      >
        {content || ''}
      </ReactMarkdown>
    </div>
  )
}
