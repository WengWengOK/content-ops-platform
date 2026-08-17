#!/usr/bin/env node
/**
 * LLM-as-Judge 评测门禁（CI）：对评估集用例调用 /observability/evals/judge 判分，
 * 任一用例低于阈值则退出码非 0，阻断发布。
 *
 * 用法：
 *   API_BASE=http://localhost:8080 EVAL_THRESHOLD=70 \
 *     node scripts/eval-gate.mjs [cases.json]
 *
 * 用例文件格式：[{"stage":"topic-planning","title":"...","input":"...","output":"..."}]
 */
import fs from 'node:fs'

const API_BASE = process.env.API_BASE || 'http://localhost:8080'
const THRESHOLD = Number(process.env.EVAL_THRESHOLD || 70)
const casesFile = process.argv[2] || 'content-ops-configs/evals/cases.json'

const cases = JSON.parse(fs.readFileSync(casesFile, 'utf-8'))

async function judge(c) {
  const res = await fetch(`${API_BASE}/api/v1/observability/evals/judge`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ stage: c.stage, input: c.input, output: c.output }),
  })
  const body = await res.json()
  if (!body.success) throw new Error(`judge 接口失败: ${body.error || body.message}`)
  return body.data
}

let failed = 0
console.log(`评估集: ${cases.length} 条 | 阈值: ${THRESHOLD} | API: ${API_BASE}`)
for (const c of cases) {
  try {
    const r = await judge(c)
    const ok = r.score != null && r.score >= THRESHOLD
    console.log(`${ok ? 'PASS' : 'FAIL'} [${c.stage}] ${c.title || c.input?.slice(0, 30) || ''} score=${r.score} ${r.feedback || ''}`)
    if (!ok) failed++
  } catch (e) {
    console.error(`ERROR [${c.stage}] ${e.message}`)
    failed++
  }
}
console.log(failed === 0 ? '✅ 评测门禁通过' : `❌ 评测门禁失败: ${failed} 条未达标`)
process.exit(failed === 0 ? 0 : 1)
