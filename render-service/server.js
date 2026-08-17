/**
 * ContentOps HTML → PNG 渲染服务
 *
 * POST /render  { files: [{ name, html, width, height }] }
 *   →  { ok: true, files: [{ name, pngBase64 }] }
 *
 * 用无头 Chromium 按精确画板尺寸截图。主服务在 /download 时调用本服务，
 * 不可用或超时时自动降级为「纯 HTML ZIP」（不影响下载功能）。
 *
 * 浏览器选择（按优先级）：
 *  1. RENDER_BROWSER_PATH   显式指定 chrome/edge 可执行文件
 *  2. RENDER_BROWSER_CHANNEL Playwright channel，如 msedge / chrome
 *  3. 默认使用 Playwright 自带的 Chromium（Docker 镜像内置）
 */
import http from 'node:http'
import { createRequire } from 'node:module'

// ESM 下 NODE_PATH 不生效，用 createRequire 兼容「本机复用 Codex 运行时依赖」与「容器内置依赖」两种环境
const require = createRequire(import.meta.url)
const { chromium } = require('playwright')

const PORT = Number(process.env.PORT || 3000)
const MAX_FILES = 40
const MAX_BODY_BYTES = 25 * 1024 * 1024
const SCREENSHOT_TIMEOUT_MS = Number(process.env.RENDER_TIMEOUT_MS || 15000)

let browserPromise = null

function browserOptions() {
  const options = {
    headless: true,
    args: [
      '--no-sandbox',
      '--disable-gpu',
      '--disable-dev-shm-usage',
      '--hide-scrollbars',
      '--force-color-profile=srgb',
    ],
  }
  if (process.env.RENDER_BROWSER_PATH) {
    options.executablePath = process.env.RENDER_BROWSER_PATH
  }
  if (process.env.RENDER_BROWSER_CHANNEL) {
    options.channel = process.env.RENDER_BROWSER_CHANNEL
  }
  return options
}

function getBrowser() {
  if (!browserPromise) {
    browserPromise = chromium.launch(browserOptions()).catch((err) => {
      browserPromise = null
      throw err
    })
  }
  return browserPromise
}

async function renderPngs(files) {
  const browser = await getBrowser()
  const results = []
  for (const file of files) {
    const width = Number(file.width)
    const height = Number(file.height)
    if (!Number.isFinite(width) || !Number.isFinite(height) || width < 1 || height < 1 || width > 4096 || height > 4096) {
      throw new Error(`invalid dimensions for ${file.name}: ${file.width}x${file.height}`)
    }
    const page = await browser.newPage({
      viewport: { width, height },
      deviceScaleFactor: 1,
    })
    try {
      await page.setContent(String(file.html || ''), {
        waitUntil: 'load',
        timeout: SCREENSHOT_TIMEOUT_MS,
      })
      const png = await page.screenshot({
        type: 'png',
        fullPage: false,
        animations: 'disabled',
      })
      results.push({ name: String(file.name), pngBase64: png.toString('base64') })
    } finally {
      await page.close().catch(() => {})
    }
  }
  return results
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = []
    let size = 0
    req.on('data', (chunk) => {
      size += chunk.length
      if (size > MAX_BODY_BYTES) {
        reject(new Error('body too large'))
        req.destroy()
        return
      }
      chunks.push(chunk)
    })
    req.on('end', () => resolve(Buffer.concat(chunks)))
    req.on('error', reject)
  })
}

function sendJson(res, status, payload) {
  const body = JSON.stringify(payload)
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
  })
  res.end(body)
}

const server = http.createServer(async (req, res) => {
  try {
    if (req.method === 'GET' && req.url === '/health') {
      sendJson(res, 200, { ok: true, service: 'contentops-render-service', pids: [process.pid] })
      return
    }

    if (req.method === 'POST' && req.url === '/render') {
      const raw = await readBody(req)
      let payload
      try {
        payload = JSON.parse(raw.toString('utf-8'))
      } catch {
        sendJson(res, 400, { ok: false, error: 'invalid JSON body' })
        return
      }
      const files = Array.isArray(payload?.files) ? payload.files : []
      if (files.length === 0) {
        sendJson(res, 400, { ok: false, error: 'files must be a non-empty array' })
        return
      }
      if (files.length > MAX_FILES) {
        sendJson(res, 400, { ok: false, error: `too many files, max ${MAX_FILES}` })
        return
      }
      const rendered = await renderPngs(files)
      sendJson(res, 200, { ok: true, count: rendered.length, files: rendered })
      return
    }

    if (req.method === 'POST' && req.url === '/measure') {
      const raw = await readBody(req)
      let payload
      try {
        payload = JSON.parse(raw.toString('utf-8'))
      } catch {
        sendJson(res, 400, { ok: false, error: 'invalid JSON body' })
        return
      }
      const files = Array.isArray(payload?.files) ? payload.files : []
      if (files.length === 0) {
        sendJson(res, 400, { ok: false, error: 'files must be a non-empty array' })
        return
      }
      const browser = await getBrowser()
      const results = []
      for (const file of files.slice(0, MAX_FILES)) {
        const width = Number(file.width)
        const height = Number(file.height)
        const page = await browser.newPage({
          viewport: { width, height },
          deviceScaleFactor: 1,
        })
        try {
          await page.setContent(String(file.html || ''), {
            waitUntil: 'load',
            timeout: SCREENSHOT_TIMEOUT_MS,
          })
          const overflowPx = await page.evaluate(() => {
            const body = document.querySelector('.card-body')
            if (!body) return 0
            const bodyRect = body.getBoundingClientRect()
            let maxBottom = bodyRect.bottom
            for (const el of body.children) {
              const r = el.getBoundingClientRect()
              const style = window.getComputedStyle(el)
              const marginBottom = parseFloat(style.marginBottom) || 0
              if (r.bottom + marginBottom > maxBottom) maxBottom = r.bottom + marginBottom
            }
            return Math.max(0, Math.round(maxBottom - bodyRect.bottom))
          })
          results.push({ name: String(file.name), overflowPx })
        } finally {
          await page.close().catch(() => {})
        }
      }
      sendJson(res, 200, { ok: true, count: results.length, files: results })
      return
    }

    sendJson(res, 404, { ok: false, error: 'not found' })
  } catch (err) {
    console.error('[render-service] error:', err)
    sendJson(res, 500, { ok: false, error: String(err?.message || err) })
  }
})

server.listen(PORT, () => {
  console.log(`[render-service] listening on :${PORT}`)
})

process.on('SIGTERM', async () => {
  if (browserPromise) {
    try {
      const browser = await browserPromise
      await browser.close()
    } catch {
      // ignore
    }
  }
  server.close(() => process.exit(0))
})
