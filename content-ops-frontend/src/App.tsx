import { Routes, Route } from 'react-router-dom'
import { Layout } from '@/components/layout/Layout'
import { Dashboard } from '@/pages/Dashboard'
import { WorkflowPage } from '@/pages/WorkflowPage'
import { DiscussionPage } from '@/pages/DiscussionPage'
import { AgentsPage } from '@/pages/AgentsPage'
import { HistoryPage } from '@/pages/HistoryPage'

export default function App() {
  return (
    <Layout>
      <Routes>
        <Route path="/" element={<Dashboard />} />
        <Route path="/workflow" element={<WorkflowPage />} />
        <Route path="/discussion" element={<DiscussionPage />} />
        <Route path="/agents" element={<AgentsPage />} />
        <Route path="/history" element={<HistoryPage />} />
      </Routes>
    </Layout>
  )
}
