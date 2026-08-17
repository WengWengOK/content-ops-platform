/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL: string
  readonly VITE_ORCHESTRATOR_URL: string
  readonly VITE_TOPIC_AGENT_URL: string
  readonly VITE_CONTENT_AGENT_URL: string
  readonly VITE_IMAGE_AGENT_URL: string
  readonly VITE_PUBLISH_AGENT_URL: string
  readonly VITE_ANALYSIS_AGENT_URL: string
  readonly VITE_OPTIMIZE_AGENT_URL: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
