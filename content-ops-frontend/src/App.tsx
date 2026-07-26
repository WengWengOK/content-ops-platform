import { Routes, Route } from 'react-router-dom'
import { Dashboard } from './pages/Dashboard'
import { CreateWorkflowPage } from './pages/CreateWorkflowPage'
import { DiscussionPage } from './pages/DiscussionPage'
import { WorkflowDetailPage } from './pages/WorkflowDetailPage'
import { WorkDetailPage } from './pages/WorkDetailPage'
import { WorkCenterPage } from './pages/WorkCenterPage'
import { DataCenterPage } from './pages/DataCenterPage'
import { UserProfilePage } from './pages/UserProfilePage'
import { PlatformAccountsPage } from './pages/PlatformAccountsPage'

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Dashboard />} />
      <Route path="/create-workflow" element={<CreateWorkflowPage />} />
      <Route path="/discussion" element={<DiscussionPage />} />
      <Route path="/workflow-detail" element={<WorkflowDetailPage />} />
      <Route path="/work-detail" element={<WorkDetailPage />} />
      <Route path="/work-center" element={<WorkCenterPage />} />
      <Route path="/data-center" element={<DataCenterPage />} />
      <Route path="/user-profile" element={<UserProfilePage />} />
      <Route path="/platform-accounts" element={<PlatformAccountsPage />} />
    </Routes>
  )
}
