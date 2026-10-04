/**
 * 平台账号凭据 API —— 多账号/多租户归属：采集/回复/通知按凭据走，避免多账号串数据。
 * 说明：令牌只在写入时提交，读取接口只返回掩码（****xxxx）。
 */
import { apiClient } from './client'
import type { AgentResponse, CredentialProbeResult, PlatformCredential } from '@/types'

function unwrap<T>(resp: AgentResponse<T>): T {
  if (resp.success) return resp.data
  throw new Error(resp.error || resp.message || 'API returned failure')
}

/** GET /platform-credentials — 我的凭据列表（令牌掩码） */
export async function listCredentials(includeShared = true): Promise<PlatformCredential[]> {
  const { data } = await apiClient.get<AgentResponse<PlatformCredential[]>>('/platform-credentials', {
    params: { includeShared },
  })
  return unwrap(data) ?? []
}

/** POST /platform-credentials — 新增账号凭据 */
export async function createCredential(body: {
  platform?: string
  accountName: string
  accountRef?: string
  preset?: string
  baseUrl: string
  accessToken?: string
  enabled?: boolean
  defaultCredential?: boolean
}): Promise<PlatformCredential> {
  const { data } = await apiClient.post<AgentResponse<PlatformCredential>>(
    '/platform-credentials',
    { platform: 'xiaohongshu', preset: 'xhs-mcp', ...body }
  )
  return unwrap(data)
}

/** PUT /platform-credentials/{id} — 更新凭据（accessToken 传空表示不变） */
export async function updateCredential(
  credentialId: string,
  body: {
    accountName?: string
    accountRef?: string
    preset?: string
    baseUrl?: string
    accessToken?: string
    enabled?: boolean
    defaultCredential?: boolean
  }
): Promise<PlatformCredential> {
  const { data } = await apiClient.put<AgentResponse<PlatformCredential>>(
    `/platform-credentials/${credentialId}`,
    body
  )
  return unwrap(data)
}

/** DELETE /platform-credentials/{id} */
export async function deleteCredential(credentialId: string): Promise<void> {
  await apiClient.delete(`/platform-credentials/${credentialId}`)
}

/** POST /platform-credentials/{id}/probe — 探测桥是否可达 + 账号是否已登录 */
export async function probeCredential(credentialId: string): Promise<CredentialProbeResult> {
  const { data } = await apiClient.post<AgentResponse<CredentialProbeResult>>(
    `/platform-credentials/${credentialId}/probe`
  )
  return unwrap(data)
}