/**
 * 评论区助手的共享常量与轻量类型（多处复用，集中维护）。
 */
export const INTENTS = ['咨询', '求教程', '售后', '吐槽', '表扬', '推广', '潜在客户', '反馈', '无关']
export const SENTIMENTS = ['POSITIVE', 'NEUTRAL', 'NEGATIVE']

export const INTENT_COLORS: Record<string, string> = {
  咨询: '#165DFF',
  求教程: '#0FC6C2',
  售后: '#F53F3F',
  吐槽: '#F53F3F',
  表扬: '#00B42A',
  推广: '#FF7D00',
  潜在客户: '#722ED1',
  反馈: '#FF9A2E',
  无关: '#86909C',
}

export const SENTIMENT_COLORS: Record<string, string> = {
  POSITIVE: '#00B42A',
  NEUTRAL: '#86909C',
  NEGATIVE: '#F53F3F',
}

export const STATUS_CN: Record<string, string> = {
  NONE: '未处理',
  DRAFT: '草稿',
  APPROVED: '已审核',
  SENT: '已发送',
}

/** 平台字节序号 → 展示名（与后端 PlatformCode 枚举严格对应，编号不可改） */
export const PLATFORM_BY_CODE: Record<number, string> = {
  0: '未知平台',
  1: '小红书',
  2: '微信公众号',
  3: '抖音',
  4: '哔哩哔哩',
  5: '快手',
}

/** 二维码色系（用于徽标） */
export const PLATFORM_COLOR: Record<number, string> = {
  1: '#FF2D5E',
  2: '#07C160',
  3: '#161823',
  4: '#FB7299',
  5: '#FF5000',
}

/** 按序号解码平台名；未知序号回退到字符串平台名（兼容历史数据）。 */
export function platformLabel(code?: number, fallback?: string): string {
  if (code != null && PLATFORM_BY_CODE[code]) return PLATFORM_BY_CODE[code]
  return fallback && fallback.trim() ? fallback : '未知平台'
}

export const RELATION_LABEL: Record<string, string> = {
  FAN: '粉丝',
  FOLLOWING: '关注',
  FRIEND: '好友',
  REGULAR: '常客',
  SELF: '自己',
  STRANGER: '路人',
}

export const RELATION_COLOR: Record<string, string> = {
  FAN: '#FF2D5E',
  FOLLOWING: '#165DFF',
  FRIEND: '#722ED1',
  REGULAR: '#FF7D00',
  SELF: '#00B42A',
  STRANGER: '#86909C',
}

export interface DialogTurn {
  role: 'user' | 'assistant'
  content: string
}