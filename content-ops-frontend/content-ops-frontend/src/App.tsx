import { Routes, Route } from 'react-router-dom'
import { Dashboard } from './pages/Dashboard'
import { CreateWorkflowPage } from './pages/CreateWorkflowPage'
import { DiscussionPage } from './pages/DiscussionPage'
import { WorkflowDetailPage } from './pages/WorkflowDetailPage'
import { WorkCenterPage } from './pages/WorkCenterPage'
import { CollectionsPage } from './pages/CollectionsPage'
import { TrendsPage } from './pages/TrendsPage'
import { CommentsPage } from './pages/CommentsPage'
import { DataCenterPage } from './pages/DataCenterPage'
import { ObservabilityPage } from './pages/ObservabilityPage'
import { UserProfilePage } from './pages/UserProfilePage'
import { PlatformAccountsPage } from './pages/PlatformAccountsPage'
import { LoginPage } from './pages/LoginPage'

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Dashboard />} />
      <Route path="/create-workflow" element={<CreateWorkflowPage />} />
      <Route path="/discussion" element={<DiscussionPage />} />
      <Route path="/login" element={<LoginPage />} />
      <Route path="/workflow-detail" element={<WorkflowDetailPage />} />
      <Route path="/work-center" element={<WorkCenterPage />} />
      <Route path="/collections" element={<CollectionsPage />} />
      <Route path="/trends" element={<TrendsPage />} />
      <Route path="/comments" element={<CommentsPage />} />
      <Route path="/data-center" element={<DataCenterPage />} />
      <Route path="/observability" element={<ObservabilityPage />} />
      <Route path="/user-profile" element={<UserProfilePage />} />
      <Route path="/platform-accounts" element={<PlatformAccountsPage />} />
    </Routes>
  )
}
