import axios from 'axios'
import type { AgentResponse, AgentTaskRequest } from '@/types'

const agentTimeout = 120000

function createAgentClient(baseURL: string) {
  return axios.create({ baseURL: `${baseURL}/api/v1`, timeout: agentTimeout, headers: { 'Content-Type': 'application/json' } })
}

const topicClient = createAgentClient(import.meta.env.VITE_TOPIC_AGENT_URL || '/topic-agent')
const contentClient = createAgentClient(import.meta.env.VITE_CONTENT_AGENT_URL || '/content-agent')
const imageClient = createAgentClient(import.meta.env.VITE_IMAGE_AGENT_URL || '/image-agent')
const publishClient = createAgentClient(import.meta.env.VITE_PUBLISH_AGENT_URL || '/publish-agent')
const analysisClient = createAgentClient(import.meta.env.VITE_ANALYSIS_AGENT_URL || '/analysis-agent')
const optimizeClient = createAgentClient(import.meta.env.VITE_OPTIMIZE_AGENT_URL || '/optimize-agent')

export const agentApi = {
  topic: {
    execute: (req: AgentTaskRequest) => topicClient.post<AgentResponse>('/topic/execute', req).then(r => r.data),
  },
  content: {
    generateOutline: (req: AgentTaskRequest) => contentClient.post<AgentResponse>('/content/outline', req).then(r => r.data),
    generateDraft: (req: AgentTaskRequest) => contentClient.post<AgentResponse>('/content/draft', req).then(r => r.data),
    execute: (req: AgentTaskRequest) => contentClient.post<AgentResponse>('/content/execute', req).then(r => r.data),
  },
  image: {
    generateStyles: (req: AgentTaskRequest) => imageClient.post<AgentResponse>('/image/styles', req).then(r => r.data),
    generateImages: (req: AgentTaskRequest) => imageClient.post<AgentResponse>('/image/generate', req).then(r => r.data),
    execute: (req: AgentTaskRequest) => imageClient.post<AgentResponse>('/image/execute', req).then(r => r.data),
  },
  publish: {
    execute: (req: AgentTaskRequest) => publishClient.post<AgentResponse>('/publish/execute', req).then(r => r.data),
  },
  analysis: {
    execute: (req: AgentTaskRequest) => analysisClient.post<AgentResponse>('/analysis/execute', req).then(r => r.data),
  },
  optimize: {
    execute: (req: AgentTaskRequest) => optimizeClient.post<AgentResponse>('/optimize/execute', req).then(r => r.data),
  },
}
