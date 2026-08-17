# ContentOps Render Service（HTML → PNG）

独立的无头渲染小服务：把 ContentOps 导出的作品卡片 / 封面 HTML 按精确画板尺寸批量截成 PNG，
供 `/workflow/{id}/download` 直接把 PNG 打进 ZIP。**渲染服务不可用时，下载自动降级为纯 HTML ZIP**，不影响主流程。

## 启动

```bash
# 本机（使用系统 Chrome/Edge，无需下载 Chromium）
set RENDER_BROWSER_CHANNEL=msedge   # Windows PowerShell
export RENDER_BROWSER_CHANNEL=chrome # macOS/Linux
npm install
npm start

# Docker（推荐生产方式，镜像内置 Chromium）
docker build -t contentops-render-service .
docker run -d -p 3000:3000 contentops-render-service
```

浏览器选择优先级：`RENDER_BROWSER_PATH` > `RENDER_BROWSER_CHANNEL` > Playwright 自带 Chromium。

## API

`GET /health` → `{ "ok": true }`

`POST /render`

```json
{
  "files": [
    { "name": "xiaohongshu/card-01.png", "html": "<!DOCTYPE html>…", "width": 1080, "height": 1440 },
    { "name": "wechat/cover-wide-21x9.png", "html": "<!DOCTYPE html>…", "width": 2100, "height": 900 }
  ]
}
```

→ `{ "ok": true, "count": 2, "files": [{ "name": "…", "pngBase64": "…" }] }`

约束：单次最多 40 个文件，单文件宽高 1–4096，请求体 ≤ 25MB，单页截图超时 15s。

## 与主服务对接

主服务配置（`application.yml`）：

```yaml
contentops:
  render-service:
    enabled: ${CONTENTOPS_RENDER_ENABLED:false}
    url: ${CONTENTOPS_RENDER_SERVICE_URL:http://localhost:3000}
    timeout-ms: ${CONTENTOPS_RENDER_TIMEOUT_MS:20000}
```

开启后，`/download` 会把小红书轮播卡、公众号封面对、抖音/B站 16:9 封面渲染成 PNG 一并打包；
渲染服务连不上或超时则跳过 PNG（仅输出 HTML），并记录 WARN 日志。

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `PORT` | 3000 | 服务端口 |
| `RENDER_BROWSER_PATH` | 无 | 显式浏览器可执行文件路径 |
| `RENDER_BROWSER_CHANNEL` | 无 | Playwright channel：`msedge` / `chrome` |
| `RENDER_TIMEOUT_MS` | 15000 | 单页截图超时 |
